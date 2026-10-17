# HumanChessAI — Human-Like Chess AI for Android

**HumanChessAI** is a free, standalone **human-like chess AI and chess analysis app for Android**. It uses **Maia3-5M** to predict the moves real human chess players are likely to make at a selected Elo, instead of showing only computer-best moves like a traditional chess engine.

If you are searching for **Human Chess AI**, **HumanChessAI APK**, **human-like chess AI Android**, **Maia chess Android**, **chess move prediction by Elo**, or a **DroidFish-style Maia chess app**, this is the official HumanChessAI project and download page.

## Download HumanChessAI APK

### [⬇️ Download the latest HumanChessAI Android APK](https://github.com/BALAJI56237/HumanChessAI/releases/download/latest-beta/HumanChessAI.apk)

Latest release page:

https://github.com/BALAJI56237/HumanChessAI/releases/tag/latest-beta

The APK runs Maia3 directly on the Android device through ONNX Runtime. After installation, chess analysis does **not** require Termux, Ubuntu, Python, Chrome, a local server, or a continuous internet connection.

## What makes HumanChessAI different?

Traditional chess engines try to find the objectively strongest move. HumanChessAI instead asks:

> **What move is a human player at this Elo most likely to play?**

You can set separate White and Black Elo ratings and see Maia's predicted human move probabilities, continuation lines, candidate arrows, and W/D/L estimates directly on the board.

HumanChessAI is useful for:

- human-like chess move prediction
- studying realistic opponent responses
- chess analysis by player rating / Elo
- practicing against more human-looking move choices
- comparing likely human moves with your own ideas
- exploring positions without relying on Stockfish-best-move output

## Main features

- **Maia3-5M human move prediction**
- **White and Black Elo conditioning**
- human continuation lines instead of Stockfish PVs
- move probabilities shown for likely human moves
- candidate arrows directly on the chess board
- W/D/L output from Maia's value head
- Fast / Balanced / Deep analysis presets
- multiple human continuation lines
- drag-and-drop pieces
- tap-to-move input
- pawn promotion chooser: Queen, Rook, Bishop, Knight
- board flip
- previous / next position navigation
- split-screen friendly Android layout
- board position preserved while resizing split screen
- multiple DroidFish-style board themes and chess piece sets
- compact DroidFish-style toolbar controls
- FEN loading/copying
- PGN copy/export support
- Android-native ONNX Runtime inference

## Game modes

HumanChessAI includes:

- **Analysis Mode**
- **Edit / re-play Game**
- **Play White**
- **Play Black**
- **Two Players**
- **Computer vs Computer**

The AI side uses Maia human move prediction rather than Stockfish-style best-move search.

## Human move probabilities, not engine evaluations

A high Maia probability means that a move is predicted to be common for a human player around the selected Elo. It does **not** necessarily mean that move is objectively the strongest chess move.

For example, HumanChessAI may prefer a natural move that humans frequently choose even when a conventional engine would prefer a different tactical or positional continuation.

## Maia3 on Android

The app runs the **Maia3-5M neural network on-device** using Microsoft ONNX Runtime for Android.

The Maia3 PyTorch checkpoint is exported to ONNX during the GitHub Actions build. The export uses an export-compatible MultiheadAttention implementation while reusing the checkpoint's trained Q/K/V and output-projection weights.

### Model verification

The exported ONNX model was checked against the original PyTorch model before Android integration. On the verification input:

- Original vs exportable PyTorch move logits: max difference about `1.14e-5`
- Exportable PyTorch vs ONNX move logits: max difference about `1.24e-5`
- all implementations returned the exact same top-5 move indices

The model uses 8 historical board states, 12 piece channels per board, and separate self/opponent Elo inputs.

## Android requirements

- Android 8.0 or newer (`minSdk 26`)
- current APK is built for `arm64-v8a` devices

Because the neural network runs locally, the APK is larger than a normal lightweight chess GUI.

## APK releases

Every successful build from the `main` branch updates the permanent GitHub release asset:

**`HumanChessAI.apk`**

Direct download:

https://github.com/BALAJI56237/HumanChessAI/releases/download/latest-beta/HumanChessAI.apk

This means the same download link can be shared publicly while the APK itself is replaced with newer builds.

## Build HumanChessAI locally

The Android app requires Java 17 and the Android SDK. Before a local Gradle build, generate the Maia ONNX assets with:

```bash
python tools/export_maia_onnx.py --output-dir app/src/main/assets/models
```

Then build the Android APK:

```bash
gradle :app:assembleDebug
```

GitHub Actions performs the complete model export and Android build automatically.

## Open-source components and attribution

HumanChessAI uses or references open-source software including:

- **Maia3** for human chess move prediction
- **ONNX Runtime** for on-device neural-network inference
- **chesslib** for chess rules and position handling
- selected **DroidFish** UI assets / chess piece sets under their applicable open-source license terms

License notices for redistributed assets are bundled with the application/build process. Review the upstream licenses before redistributing modified builds.

## Project name

The official project name is **HumanChessAI** (also written as **Human Chess AI**).

Official GitHub repository:

https://github.com/BALAJI56237/HumanChessAI

Official Android APK download:

https://github.com/BALAJI56237/HumanChessAI/releases/download/latest-beta/HumanChessAI.apk

---

**Search terms:** HumanChessAI, Human Chess AI, HumanChessAI APK, human-like chess AI, human chess move predictor, Maia chess Android, Maia3 Android, chess AI by Elo, human move prediction chess, Android chess analysis app.
