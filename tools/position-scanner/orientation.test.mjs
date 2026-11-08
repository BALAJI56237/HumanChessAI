import assert from "node:assert/strict";
import { test } from "node:test";
import { canonicalizeScan, canonicalIndex, parsePlacement, placementFromSquares, pawnOrientation } from "./orientation.js";

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
