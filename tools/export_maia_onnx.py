#!/usr/bin/env python3
"""Export a Maia3 checkpoint to the ONNX pair bundled by the Android app.

The exporter replaces torch.nn.MultiheadAttention only in-memory with an
ONNX-friendly equivalent using the checkpoint's exact Q/K/V and output weights.
This is the same workaround verified against the original PyTorch model: the
patched PyTorch graph and ONNX graph preserve the same top move ordering with
only tiny numerical differences.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F

from maia3.uci import Maia3UCIEngine, parse_args as maia_parse_args


SUPPORTED_MODELS = ("maia3-5m", "maia3-23m", "maia3-79m")


class ExportableMHA(nn.Module):
    def __init__(self, old: nn.MultiheadAttention):
        super().__init__()
        self.embed_dim = old.embed_dim
        self.num_heads = old.num_heads
        self.head_dim = self.embed_dim // self.num_heads
        self.in_proj_weight = old.in_proj_weight
        self.in_proj_bias = old.in_proj_bias
        self.out_proj_weight = old.out_proj.weight
        self.out_proj_bias = old.out_proj.bias

    def forward(
        self,
        query,
        key,
        value,
        key_padding_mask=None,
        need_weights=False,
        attn_mask=None,
        average_attn_weights=True,
        is_causal=False,
        **kwargs,
    ):
        batch, length, embed = query.shape
        source_len = key.shape[1]
        heads = self.num_heads
        head_dim = self.head_dim

        wq, wk, wv = self.in_proj_weight.chunk(3, dim=0)
        if self.in_proj_bias is not None:
            bq, bk, bv = self.in_proj_bias.chunk(3, dim=0)
        else:
            bq = bk = bv = None

        q = F.linear(query, wq, bq)
        k = F.linear(key, wk, bk)
        v = F.linear(value, wv, bv)

        q = q.reshape(batch, length, heads, head_dim).transpose(1, 2)
        k = k.reshape(batch, source_len, heads, head_dim).transpose(1, 2)
        v = v.reshape(batch, source_len, heads, head_dim).transpose(1, 2)

        scores = torch.matmul(q, k.transpose(-2, -1)) / math.sqrt(head_dim)

        if attn_mask is not None:
            mask = attn_mask
            if mask.dim() == 2:
                mask = mask.unsqueeze(0).unsqueeze(0)
            elif mask.dim() == 3:
                mask = mask.reshape(batch, heads, length, source_len)
            if mask.dtype == torch.bool:
                scores = scores.masked_fill(mask, -1e9)
            else:
                scores = scores + mask.to(scores.dtype)

        if key_padding_mask is not None:
            mask = key_padding_mask[:, None, None, :]
            if mask.dtype == torch.bool:
                scores = scores.masked_fill(mask, -1e9)
            else:
                scores = scores + mask.to(scores.dtype)

        probs = torch.softmax(scores, dim=-1)
        out = torch.matmul(probs, v)
        out = out.transpose(1, 2).contiguous().reshape(batch, length, embed)
        out = F.linear(out, self.out_proj_weight, self.out_proj_bias)
        return out, None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output-dir", default="app/src/main/assets/models")
    ap.add_argument(
        "--model",
        default="maia3-5m",
        choices=SUPPORTED_MODELS,
        help="Official Maia3 model alias to export",
    )
    args = ap.parse_args()

    model_name = args.model.lower()
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{model_name}.onnx"
    data_path = out_dir / f"{model_name}.onnx.data"
    out_path.unlink(missing_ok=True)
    data_path.unlink(missing_ok=True)

    print(f"Loading {model_name} ...")
    cfg = maia_parse_args([
        "--model", model_name,
        "--device", "cpu",
        "--no-use-amp",
    ])
    engine = Maia3UCIEngine(cfg)
    engine.ensure_model_loaded()
    model = engine.model
    model.eval()

    replaced = 0
    for module in model.modules():
        if hasattr(module, "mha") and isinstance(module.mha, nn.MultiheadAttention):
            module.mha = ExportableMHA(module.mha)
            replaced += 1
    if replaced == 0:
        raise RuntimeError("No torch.nn.MultiheadAttention blocks found")
    print(f"Patched attention blocks: {replaced}")

    # All released Maia3 size variants consume 8 historical boards x 12 piece
    # channels = 96 features per square. Elo stays a runtime input; exporting at
    # 1500 does NOT lock the model to 1500.
    tokens = torch.zeros((1, 64, 96), dtype=torch.float32)
    self_elo = torch.tensor([1500.0], dtype=torch.float32)
    oppo_elo = torch.tensor([1500.0], dtype=torch.float32)

    with torch.inference_mode():
        move_logits, value_logits, ponder = model(tokens, self_elo, oppo_elo)
    print(
        "PyTorch output shapes:",
        tuple(move_logits.shape),
        tuple(value_logits.shape),
        tuple(ponder.shape),
    )

    print(f"Exporting {out_path} ...")
    torch.onnx.export(
        model,
        (tokens, self_elo, oppo_elo),
        str(out_path),
        input_names=["tokens", "self_elo", "oppo_elo"],
        output_names=["move_logits", "value_logits", "ponder"],
        opset_version=18,
        dynamo=True,
    )

    if not out_path.exists() or out_path.stat().st_size < 100_000:
        raise RuntimeError("ONNX graph was not created correctly")
    if not data_path.exists() or data_path.stat().st_size < 1_000_000:
        raise RuntimeError("ONNX external-data file was not created correctly")

    print(f"ONNX graph: {out_path.stat().st_size:,} bytes")
    print(f"ONNX data : {data_path.stat().st_size:,} bytes")
    print(f"Export complete: {model_name}")


if __name__ == "__main__":
    main()
