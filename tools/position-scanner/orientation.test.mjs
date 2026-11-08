import assert from "node:assert/strict";
import { test } from "node:test";
import { canonicalizeScan, canonicalIndex, orientScannedPosition, squareAtDisplayIndex, parsePlacement, placementFromSquares, pawnOrientation } from "./orientation.js";

const standard = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";
const reversed = "RNBKQBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbkqbnr";
// Actual image from the reported failure, with White physically at top and Black bottom.
const failure = "R2KQB1R/PPP1PPPP/2N2N2/3P1B2/3p4/2n2n2/ppp1pppp/r1bkqb1r";

test("normal starting position stays in canonical FEN", () => {
  const r = canonicalizeScan(standard, standard, "white");
  assert.equal(placementFromSquares(r.squares), standard);
  assert.equal(r.blackAtBottom, false);
});

test("reversed black-at-bottom scan rotates actual FEN to restore pawn direction", () => {
  const r = canonicalizeScan(reversed, reversed, "white");
  assert.equal(r.blackAtBottom, true);
  assert.equal(r.automaticallyCorrected, true);
  assert.equal(placementFromSquares(r.squares), standard);
  assert.ok(pawnOrientation(r.squares).score > 0);
});

test("reported scanned screenshot yields Black pawns on top, never rank 2", () => {
  const r = canonicalizeScan(failure, failure, "white");
  assert.equal(r.blackAtBottom, true);
  assert.equal(r.automaticallyCorrected, true);
  assert.equal(pawnOrientation(r.squares).score > 0, true);
  assert.match(placementFromSquares(r.squares).split("/")[1], /p/);
  assert.match(placementFromSquares(r.squares).split("/")[6], /P/);
});

test("flip view changes only visual square mapping, not internal FEN", () => {
  const input=parsePlacement(standard);
  const initial=placementFromSquares(input);
  assert.equal(canonicalIndex(0, false), 0);
  assert.equal(canonicalIndex(0, true), 63);
  assert.equal(canonicalIndex(63, true), 0);
  assert.equal(placementFromSquares(input), initial);
});

test("FEN rank length validation rejects corrupt OCR", () => {
  assert.throws(()=>parsePlacement("8/8/8/8/8/8/8/7"));
});

/**
 * Verified against the user's actual chess.com screenshot:
 * top-left (White side) = h1 rook; bottom-left (Black side) = h8 rook;
 * White played exd5 and Black is next.
 * Screenshot perspective is Black-at-bottom: image rows show 1->8,
 * image columns show h->a. FEN MUST be canonical 8->1 and a->h.
 */
const chessComBlackViewRaw = "R2KQB1R/PPP1PPPP/2N2N2/5B2/3pP3/2n2n2/ppp2ppp/r1bkqb1r";
const chessComCorrectFen = "r1bqkb1r/ppp2ppp/2n2n2/3Pp3/2B5/2N2N2/PPPP1PPP/R1BQK2R";

test("Chess.com Black-at-bottom screenshot recognizes exact FEN with e8 king and d8 queen", () => {
  const r = orientScannedPosition(chessComBlackViewRaw,"auto");
  assert.equal(r.blackAtBottom,true);
  assert.equal(placementFromSquares(r.squares),chessComCorrectFen);
  assert.equal(r.squares[4],"k");    // e8 black king
  assert.equal(r.squares[3],"q");    // d8 black queen
  assert.equal(r.squares[28],"p");   // e5 black pawn
  assert.equal(r.squares[27],"P");   // d5 white pawn from exd5
  assert.equal(r.squares[36],".");   // e4 is available for black pawn push
});

test("Chess.com visual black-bottom coordinates are h to a, rank 1 to rank 8",()=>{
  const topLeft=squareAtDisplayIndex(0,true);
  const topRight=squareAtDisplayIndex(7,true);
  const bottomLeft=squareAtDisplayIndex(56,true);
  const bottomRight=squareAtDisplayIndex(63,true);
  assert.deepEqual(topLeft,{index:63,file:"h",rank:1});
  assert.deepEqual(topRight,{index:56,file:"a",rank:1});
  assert.deepEqual(bottomLeft,{index:7,file:"h",rank:8});
  assert.deepEqual(bottomRight,{index:0,file:"a",rank:8});
  const s=orientScannedPosition(chessComBlackViewRaw,"black").squares;
  assert.equal(s[topLeft.index],"R");
  assert.equal(s[bottomLeft.index],"r");
  assert.equal(s[bottomRight.index],"r");
});

test("Choosing Black/White perspective changes FEN once from raw, flip view never changes FEN",()=>{
  const r=orientScannedPosition(chessComBlackViewRaw,"black");
  assert.equal(placementFromSquares(r.squares),chessComCorrectFen);
  assert.equal(r.blackAtBottom,true);
  const wrong=orientScannedPosition(chessComBlackViewRaw,"white");
  assert.equal(placementFromSquares(wrong.squares),chessComBlackViewRaw);
  const again=orientScannedPosition(chessComBlackViewRaw,"black");
  assert.equal(placementFromSquares(again.squares),chessComCorrectFen);
  assert.equal(squareAtDisplayIndex(0,false).file,"a");
  assert.equal(squareAtDisplayIndex(0,true).file,"h");
});
