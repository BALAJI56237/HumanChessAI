package com.humanchessai.app

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move

/**
 * Small compatibility helpers for chesslib 1.3.x.
 *
 * Move.toString() is chesslib's canonical long algebraic / UCI-style
 * representation (for example e2e4 or a7a8q). The Move(String, Side)
 * constructor parses the same representation and selects the correct
 * promotion-piece colour from the board's side to move.
 */
internal fun Board.toUci(move: Move): String = move.toString().lowercase()

internal fun Board.fromUci(uci: String): Move = Move(uci.lowercase(), sideToMove)
