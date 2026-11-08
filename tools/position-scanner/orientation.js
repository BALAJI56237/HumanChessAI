/**
 * FEN coordinates are always chess coordinates: White moves toward rank 8,
 * Black toward rank 1, regardless of the board orientation in the image.
 * Keep the scanner's VIEW orientation separate from its position.
 */
export function parsePlacement(placement) {
  const rows = String(placement).split("/");
  if (rows.length !== 8) throw new Error("Invalid scanned board (expected 8 ranks)");
  const out = [];
  for (const row of rows) {
    const rank = [];
    for (const ch of row) {
      if (/^[1-8]$/.test(ch)) rank.push(...Array(Number(ch)).fill("."));
      else if (/^[KQRBNPkqrbnp]$/.test(ch)) rank.push(ch);
      else throw new Error("Unexpected chess piece in scan");
    }
    if (rank.length !== 8) throw new Error("Invalid scanned rank");
    out.push(...rank);
  }
  if (out.length !== 64) throw new Error("Scanned board must contain 64 squares");
  return out;
}

export function placementFromSquares(squares) {
  if (squares.length !== 64) throw new Error("Board must contain 64 squares");
  const rows = [];
  for (let r = 0; r < 8; r++) {
    let row = "", blanks = 0;
    for (let c = 0; c < 8; c++) {
      const pc = squares[r * 8 + c];
      if (pc === ".") blanks++;
      else {
        if (!/^[KQRBNPkqrbnp]$/.test(pc)) throw new Error("Invalid piece");
        if (blanks) row += blanks;
        row += pc;
        blanks = 0;
      }
    }
    if (blanks) row += blanks;
    rows.push(row);
  }
  return rows.join("/");
}

/** Positive when the bulk of black pawns are above white pawns in FEN. */
export function pawnOrientation(squares) {
  let white = 0, black = 0, whiteRanks = 0, blackRanks = 0;
  for (let i = 0; i < 64; i++) {
    const rank = 8 - Math.floor(i / 8);
    if (squares[i] === "P") { white++; whiteRanks += rank; }
    if (squares[i] === "p") { black++; blackRanks += rank; }
  }
  if (!white || !black) return { score: null, white, black };
  return { score: blackRanks / black - whiteRanks / white, white, black };
}

/**
 * On opening/middlegame screenshots with many pawns, the sign of the average
 * pawn ranks determines whether the image was photographed from Black's side.
 * In unusual endgames or pawnless positions we defer to the upstream hint.
 */
export function canonicalizeScan(rawPlacement, hintedPlacement, hint = "white") {
  const raw = parsePlacement(rawPlacement);
  const pawns = pawnOrientation(raw);
  if (pawns.white >= 3 && pawns.black >= 3 && Math.abs(pawns.score) >= 1.5) {
    const blackAtBottom = pawns.score < 0;
    return {
      squares: blackAtBottom ? raw.slice().reverse() : raw,
      blackAtBottom,
      automaticallyCorrected: blackAtBottom,
      basedOnPawns: true
    };
  }
  const suggested = parsePlacement(hintedPlacement || rawPlacement);
  const hintScore = pawnOrientation(suggested);
  const likelyReversed = hintScore.white >= 3 && hintScore.black >= 3 && hintScore.score < -1.5;
  return {
    squares: likelyReversed ? suggested.slice().reverse() : suggested,
    blackAtBottom: likelyReversed ? hint !== "black" : hint === "black",
    automaticallyCorrected: likelyReversed,
    basedOnPawns: false
  };
}

/**
 * A scanner image is read top-to-bottom, left-to-right, regardless of how the
 * original chessboard is oriented. An image with Black at the bottom must
 * rotate its array by 180 degrees before the array is serialized to FEN.
 *
 * Explicit user choice takes precedence over the pawn-based auto detection.
 * This method always starts with the original raw scan, never the current
 * editable board, so toggling the perspective cannot flip coordinates twice.
 */
export function orientScannedPosition(rawPlacement, perspective = "auto", resolvedPlacement = rawPlacement, resolvedHint = "white") {
  if (perspective === "auto") {
    return canonicalizeScan(rawPlacement, resolvedPlacement, resolvedHint);
  }
  if (perspective !== "white" && perspective !== "black") {
    throw new Error("Perspective must be auto, white or black");
  }
  const raw = parsePlacement(rawPlacement);
  const blackAtBottom = perspective === "black";
  return {
    squares: blackAtBottom ? raw.slice().reverse() : raw,
    blackAtBottom,
    automaticallyCorrected: blackAtBottom,
    basedOnPawns: false,
    chosenByUser: true
  };
}

/**
 * Returns coordinate labels for an actual visible square, never altering the
 * stored FEN. For Black at bottom the top-left is h1 and bottom-left is h8.
 */
export function squareAtDisplayIndex(i, flipped = false) {
  if (!Number.isInteger(i) || i < 0 || i >= 64) throw new Error("Invalid board index");
  const j = canonicalIndex(i, flipped);
  return {
    index: j,
    file: "abcdefgh"[j % 8],
    rank: 8 - Math.floor(j / 8),
  };
}

/** Grid index maps visual square to canonical FEN square. */
export function canonicalIndex(visibleIndex, flipped) {
  return flipped ? 63 - visibleIndex : visibleIndex;
}
