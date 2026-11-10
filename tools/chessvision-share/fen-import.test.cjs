const {test}=require("node:test");
const assert=require("node:assert/strict");
const {hcaExtractSharedFen}=require("../../app/src/main/assets/chessvision_share.js");

const screenshot="r1bqkb1r/ppp2ppp/2n2n2/3Pp3/2B5/2N2N2/PPPP1PPP/R1BQK2R b - - 0 5";

test("Chessvision.ai complete FEN preserves Black to move and chess coordinates",()=>{
  const v=hcaExtractSharedFen("FEN Text: "+screenshot+"\nCopied from Chessvision.ai");
  assert.equal(v.fen,screenshot);
  assert.equal(v.turn,"b");
  assert.equal(v.inferredTurn,false);
});

test("Bare board placement requires manual turn confirmation",()=>{
  const v=hcaExtractSharedFen("FEN: rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR");
  assert.equal(v.turn,"w");
  assert.equal(v.inferredTurn,true);
  assert.equal(v.fen,"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1");
});

test("Reject game link, PGN, malformed FEN ranks and missing kings",()=>{
  assert.equal(hcaExtractSharedFen("https://www.chess.com/game/live/123"),null);
  assert.equal(hcaExtractSharedFen("1. e4 e5 2. Nf3 Nc6"),null);
  assert.equal(hcaExtractSharedFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN w KQkq - 0 1"),null);
  assert.equal(hcaExtractSharedFen("8/8/8/8/8/8/8/8 w - - 0 1"),null);
});

test("Starting position FEN and en-passant/castling preserved",()=>{
  const fen="rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  assert.equal(hcaExtractSharedFen(fen).fen,fen);
});

test("A text share can include more than one line",()=>{
  const fen="r3k2r/ppp2ppp/8/4Pp2/8/8/PPPP2PP/R3K2R w KQkq f6 0 12";
  assert.equal(hcaExtractSharedFen("Chess position\n"+fen+"\nStudy this later").fen,fen);
});
