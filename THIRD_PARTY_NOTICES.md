# Third-party notices

HumanChessAI uses third-party open-source components and model data. This file records the major items relevant to the Android build.

## Maia-3

HumanChessAI uses Maia-3 human chess models and code from the University of Toronto CSSLab Maia-3 project. The Android build converts the official Maia3-5M, Maia3-23M, and Maia3-79M checkpoints to ONNX for on-device inference. Elo/rating is supplied to Maia as a runtime conditioning input and is not a separate model checkpoint.

Upstream: https://github.com/CSSLab/maia3

The build is pinned to Maia-3 commit `1e13597c42d4858b7cfd7cfdae01e297263364b2`. That upstream project includes the GNU Affero General Public License version 3. Review the upstream repository, model cards, and license terms before redistributing builds.

## DroidFish

HumanChessAI's chessboard interaction, menu organization, board color presets, toolbar assets, and optional SVG chess-piece sets are inspired by / sourced from DroidFish by Peter Osterlund.

Upstream: https://github.com/peterosterlund2/droidfish

DroidFish is licensed under GNU GPL version 3 or later. The APK build downloads the piece-set ZIP files and UI assets from the upstream DroidFish repository and bundles a copy of DroidFish's `COPYING` file at `assets/licenses/DROIDFISH_GPLv3.txt`.

## Stockfish

HumanChessAI bundles the official Stockfish 19 Android ARM64 executable for position evaluation. Stockfish is kept separate from Maia's human-move prediction: Stockfish supplies the evaluation score/bar while Maia supplies the human-like move probabilities and continuation lines.

Upstream source/tag: https://github.com/official-stockfish/Stockfish/tree/sf_19

Official release: https://github.com/official-stockfish/Stockfish/releases/tag/sf_19

Stockfish is licensed under GNU GPL version 3. The APK build bundles the upstream `Copying.txt` license at `assets/licenses/STOCKFISH_GPLv3.txt`.
