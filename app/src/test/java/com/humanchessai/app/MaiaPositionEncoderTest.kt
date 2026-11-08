package com.humanchessai.app

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiaPositionEncoderTest {
    // Exact Chess.com scan discussed in the issue: White's exd5 was just played,
    // so it is Black's turn. The model MUST NOT see the default initial board.
    private val screenshotFen =
        "r1bqkb1r/ppp2ppp/2n2n2/3Pp3/2B5/2N2N2/PPPP1PPP/R1BQK2R b - - 0 5"
    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private fun feature(t: FloatArray, squareIndex: Int, sideChannel: Int, historyIndex: Int = 7) =
        t[squareIndex * 96 + historyIndex * 12 + sideChannel]

    @Test fun importedBlackToMoveFenProducesItsOwnBoardTokens() {
        val fromScreenshot = MaiaPositionEncoder.buildTokens(screenshotFen, emptyList())
        val fromOpening = MaiaPositionEncoder.buildTokens(startFen, emptyList())

        assertEquals(64 * 96, fromScreenshot.size)
        assertFalse("Imported image must not encode a regular starting board",
            fromScreenshot.contentEquals(fromOpening))
        // In black-to-move encoding, Black's pawn on e5 maps to e4, own pawn channel 0.
        assertEquals(1f, feature(fromScreenshot, 28, 0))
        // White's pawn on d5 maps to d4, opponent pawn channel 6.
        assertEquals(1f, feature(fromScreenshot, 27, 6))
        // The source e2 square must NOT be filled as a white starting pawn.
        assertEquals(0f, feature(fromScreenshot, 12, 0))
    }

    @Test fun subsequentBlackMoveReplaysFromScreenshotFenNotInitialPosition() {
        val board = Board().apply { loadFromFen(screenshotFen) }
        assertEquals(Side.BLACK, board.sideToMove)
        val legalMoves = board.legalMoves().map { board.toUci(it) }
        assertTrue("Black can play e5e4", legalMoves.contains("e5e4"))
        assertFalse("White's e2e4 is not Black's move", legalMoves.contains("e2e4"))

        val afterMove = MaiaPositionEncoder.buildTokens(screenshotFen, listOf("e5e4"))
        // After Black plays e5e4, White moves next, so e4 is a black pawn (channel 6).
        assertEquals(1f, feature(afterMove, 28, 6))
        assertEquals(0f, feature(afterMove, 36, 6))
        // The first history slot remains the original scanned position.
        assertEquals(1f, feature(afterMove, 28, 0, 6))
    }

    @Test fun differentImportedFenCannotReuseOpeningMoveCache() {
        val root = MaiaPositionEncoder.cacheKey(
            "maia3-5m", startFen, startFen, emptyList(), 1500, 1500, 3, true
        )
        val imported = MaiaPositionEncoder.cacheKey(
            "maia3-5m", screenshotFen, screenshotFen, emptyList(), 1500, 1500, 3, true
        )
        assertFalse("Old history-only cache reuses White's e4 after a Black scan",
            root == imported)
        val anotherMove = MaiaPositionEncoder.cacheKey(
            "maia3-5m", screenshotFen, screenshotFen, listOf("e5e4"), 1500, 1500, 3, true
        )
        assertFalse(imported == anotherMove)
    }

    @Test fun unreplayableOpeningMoveIsRejectedInsteadOfProducingWrongTokens() {
        var threw = false
        try {
            MaiaPositionEncoder.buildTokens(screenshotFen, listOf("e2e4"))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
