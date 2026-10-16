# HumanChessAI

HumanChessAI is a standalone Android chess analysis app focused on **human move prediction** instead of engine-best-move analysis.

It uses the Maia3-5M neural network on-device through ONNX Runtime. After the APK is installed, analysis does not require Termux, Ubuntu, Python, Chrome, a local server, or an internet connection.

## Current features

- Maia3-5M human move probabilities
- White and Black Elo conditioning
- Human continuation lines instead of Stockfish PVs
- Candidate arrows directly on the board
- Fast / Balanced / Deep analysis presets
- Drag-and-drop and tap-to-move
- Board flip
- Back / forward position navigation
- Analysis Mode
- Edit/re-play Game
- Play White
- Play Black
- Two Players
- Computer vs Computer
- W/D/L output from Maia's value head
- Android-native ONNX Runtime inference

## Model verification

The Maia3-5M PyTorch checkpoint was exported to ONNX with an export-only MultiheadAttention replacement that reuses the checkpoint's exact trained Q/K/V and output-projection weights.

The exported model was checked against the original PyTorch model before Android integration. On the verification input:

- Original vs exportable PyTorch move logits: max difference about `1.14e-5`
- Exportable PyTorch vs ONNX move logits: max difference about `1.24e-5`
- The exact same top-5 move indices were returned by all three implementations

The model uses 8 historical board states, 12 piece channels per board, and separate self/opponent Elo inputs.

## APK build

The repository intentionally does **not** store the ~20 MB ONNX external-data file in Git.

GitHub Actions automatically:

1. installs Maia3 and the ONNX exporter,
2. downloads the Maia3-5M checkpoint,
3. exports the verified ONNX graph and weights,
4. bundles them into the Android APK,
5. builds `app-debug.apk`, and
6. publishes it as the `HumanChessAI-debug-apk` workflow artifact.

Open the repository's **Actions** tab, open the latest **Build Android APK** run, then download the `HumanChessAI-debug-apk` artifact.

## Local Android build

The Android app requires Java 17 and Android SDK 35. Before a local Gradle build, generate the model assets with:

```bash
python tools/export_maia_onnx.py --output-dir app/src/main/assets/models
```

Then build:

```bash
gradle :app:assembleDebug
```

## Notes

This project is a human-behavior chess predictor, not a replacement for a conventional best-move chess engine. A high Maia probability means a move is predicted to be likely for a human at the selected rating level; it does not mean the move is objectively strongest.

Maia3 is a separate upstream project. Review its license and attribution requirements before redistributing builds or publishing this project more broadly.
