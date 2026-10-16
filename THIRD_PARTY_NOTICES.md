# Third-party notices

HumanChessAI uses third-party open-source components and data. This file records the major items relevant to the Android build.

## Maia-3

HumanChessAI uses the Maia-3 human chess model and code from the University of Toronto CSSLab Maia-3 project. The Android build converts the Maia3-5M checkpoint to ONNX for on-device inference. Review the upstream Maia-3 repository and license before redistributing builds.

Upstream: https://github.com/CSSLab/maia3

## DroidFish

HumanChessAI's chessboard interaction, menu organization, board color presets, and optional SVG chess-piece sets are inspired by / sourced from DroidFish by Peter Osterlund.

Upstream: https://github.com/peterosterlund2/droidfish

DroidFish is licensed under GNU GPL version 3 or later. The APK build downloads the piece-set ZIP files from the upstream DroidFish repository and bundles a copy of DroidFish's `COPYING` file at `assets/licenses/DROIDFISH_GPLv3.txt`.

HumanChessAI does **not** bundle or use Stockfish. Maia3-5M remains the move-prediction model.
