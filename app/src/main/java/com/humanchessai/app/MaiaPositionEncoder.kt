package com.humanchessai.app

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square

/**
 * Maia3's eight historical boards must be built from the actual root position.
 *
 * A screenshot imported through /api/load-fen is NOT a continuation of the
 * standard chess starting position. In particular, a position imported with
 * Black to move and no prior moves cannot be encoded from the start position:
 * it would make Maia suggest 1.e4 for White even though Black is to move.
 *
 * Root FEN is captured by HumanChessController on reset/import, and subsequent
 * UCI moves are replayed relative to that root. The latest of the eight
 * board states is always the currently analyzed board, including its turn.
 */
internal object MaiaPositionEncoder {
    fun cacheKey(
        modelName: String,
        boardFen: String,
        initialFen: String,
        history: List<String>,
        whiteElo: Int,
        blackElo: Int,
        topN: Int,
        needWdl: Boolean,
    ): String = listOf(
        modelName,
        boardFen,
        initialFen,
        history.joinToString(","),
        whiteElo.toString(),
        blackElo.toString(),
        topN.toString(),
        needWdl.toString(),
    ).joinToString("|")

    fun buildTokens(initialFen: String, history: List<String>): FloatArray {
        val replay = Board().apply {
            setEnableEvents(false)
            loadFromFen(initialFen)
        }
        val states = ArrayList<FloatArray>()
        states += tokenizeBoard(replay)

        for (uci in history) {
            val legal = replay.legalMoves().firstOrNull {
                replay.toUci(it).equals(uci, ignoreCase = true)
            } ?: throw IllegalArgumentException(
                "Move $uci cannot be replayed from imported FEN"
            )
            replay.doMove(legal)
            states += tokenizeBoard(replay)
        }

        val recent = states.takeLast(8)
        val padded = ArrayList<FloatArray>(8)
        repeat(8 - recent.size) { padded += recent.first() }
        padded.addAll(recent)

        val out = FloatArray(64 * 96)
        for (sq in 0 until 64) {
            for (h in 0 until 8) {
                val src = padded[h]
                for (c in 0 until 12) {
                    out[sq * 96 + h * 12 + c] = src[sq * 12 + c]
                }
            }
        }
        return out
    }

    private fun tokenizeBoard(board: Board): FloatArray {
        val out = FloatArray(64 * 12)
        val blackToMove = board.sideToMove == Side.BLACK
        for (sq in Square.values()) {
            if (sq == Square.NONE) continue
            val piece = board.getPiece(sq)
            if (piece == Piece.NONE) continue

            val sourceIndex = sq.ordinal
            val file = sourceIndex % 8
            val rank = sourceIndex / 8
            val targetIndex = if (blackToMove) (7 - rank) * 8 + file else sourceIndex

            val mapped = when (piece.pieceType) {
                PieceType.PAWN -> 1
                PieceType.KNIGHT -> 2
                PieceType.BISHOP -> 3
                PieceType.ROOK -> 4
                PieceType.QUEEN -> 5
                PieceType.KING -> 6
                else -> 0
            }
            if (mapped == 0) continue

            val originalSide = piece.pieceSide
            val tokenSide = if (!blackToMove) originalSide
                else if (originalSide == Side.WHITE) Side.BLACK else Side.WHITE
            val channel = (mapped - 1) + if (tokenSide == Side.BLACK) 6 else 0
            out[targetIndex * 12 + channel] = 1f
        }
        return out
    }
}
