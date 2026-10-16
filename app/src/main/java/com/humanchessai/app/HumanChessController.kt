package com.humanchessai.app

import android.content.Context
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal data class NativeResponse(val status: Int, val body: JSONObject)

internal class HumanChessController(
    private val context: Context,
    private val onModelReady: () -> Unit,
) : Closeable {
    private val lock = Any()
    private val loader = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    private var board = Board().apply { setEnableEvents(false) }
    private val moveHistory = mutableListOf<String>()
    private val redoHistory = mutableListOf<String>()

    @Volatile private var engine: MaiaOnnxEngine? = null
    @Volatile private var predictorError: String? = null

    private var whiteElo = 1500
    private var blackElo = 1500
    private var topN = 3
    private var pvCount = 1
    private var pvPlies = 4
    private var analysisProfile = "fast"
    private var gameMode = "two"

    init {
        loader.execute {
            try {
                engine = MaiaOnnxEngine(context)
            } catch (t: Throwable) {
                predictorError = t.message ?: t.javaClass.simpleName
            } finally {
                onModelReady()
            }
        }
    }

    fun handle(path: String, body: JSONObject): NativeResponse = when (path) {
        "/api/state" -> ok(baseState())
        "/api/move" -> move(body)
        "/api/mode" -> mode(body)
        "/api/predictions" -> predictions(body)
        "/api/ai-move" -> aiMove()
        "/api/undo" -> undo()
        "/api/redo" -> redo()
        "/api/reset" -> reset()
        "/api/load-fen" -> loadFen(body)
        "/api/settings" -> settings(body)
        "/api/pv" -> pv(body)
        "/api/line" -> line(body)
        else -> error(404, "Unknown API path: $path")
    }

    private fun move(body: JSONObject): NativeResponse {
        val from = body.optString("from", "").lowercase()
        val to = body.optString("to", "").lowercase()
        val promotion = body.optString("promotion", "").lowercase().takeIf { it.isNotBlank() }
        if (from.length != 2 || to.length != 2) return error(400, "Invalid move format")

        return try {
            synchronized(lock) {
                playLocked(from, to, promotion, clearRedo = true)
                generation.incrementAndGet()
            }
            ok(baseState())
        } catch (t: IllegalArgumentException) {
            error(400, t.message ?: "Illegal move")
        }
    }

    private fun mode(body: JSONObject): NativeResponse {
        val requested = body.optString("mode", "").trim().lowercase()
        val allowed = setOf("analysis", "edit", "white", "black", "two", "cvc")
        if (requested !in allowed) return error(400, "Invalid game mode")
        synchronized(lock) { gameMode = requested }
        return ok(baseState())
    }

    private fun settings(body: JSONObject): NativeResponse {
        return try {
            synchronized(lock) {
                whiteElo = body.optInt("white_elo", whiteElo).coerceIn(0, 5000)
                blackElo = body.optInt("black_elo", blackElo).coerceIn(0, 5000)
                topN = body.optInt("top_n", topN).coerceIn(1, 20)
                pvCount = body.optInt("pv_count", pvCount).coerceIn(1, 4)
                pvPlies = body.optInt("pv_plies", pvPlies).coerceIn(2, 16)
                val p = body.optString("analysis_profile", analysisProfile).trim().lowercase()
                analysisProfile = if (p in setOf("fast", "balanced", "deep", "custom")) p else "custom"
                generation.incrementAndGet()
            }
            ok(baseState())
        } catch (_: Throwable) {
            error(400, "Invalid settings")
        }
    }

    private data class Snapshot(
        val fen: String,
        val history: List<String>,
        val generation: Int,
        val whiteElo: Int,
        val blackElo: Int,
        val topN: Int,
        val pvCount: Int,
        val pvPlies: Int,
    )

    private fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            fen = board.fen,
            history = moveHistory.toList(),
            generation = generation.get(),
            whiteElo = whiteElo,
            blackElo = blackElo,
            topN = topN,
            pvCount = pvCount,
            pvPlies = pvPlies,
        )
    }

    private fun boardFromFen(fen: String): Board = Board().apply {
        setEnableEvents(false)
        loadFromFen(fen)
    }

    private fun predictions(body: JSONObject): NativeResponse {
        val predictor = engine ?: return error(503, predictorError ?: "Maia model is still loading")
        val snap = snapshot()
        val requested = if (body.has("generation")) body.optInt("generation", -1) else null
        if (requested != null && requested != snap.generation) return staleResponse()

        return try {
            val local = boardFromFen(snap.fen)
            val preds = predictor.predict(
                local,
                snap.history,
                snap.whiteElo,
                snap.blackElo,
                snap.topN,
                needWdl = true,
            )
            if (snap.generation != generation.get()) return staleResponse()
            val arr = JSONArray()
            preds.forEach { arr.put(predictionJson(it)) }
            ok(JSONObject().put("predictions", arr).put("generation", snap.generation))
        } catch (t: Throwable) {
            error(500, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun aiMove(): NativeResponse {
        val predictor = engine ?: return error(503, predictorError ?: "Maia model is still loading")
        val snap = snapshot()
        val local = boardFromFen(snap.fen)
        if (local.isMated || local.isStaleMate || local.isDraw) return ok(baseState())

        return try {
            val pred = predictor.predict(
                local,
                snap.history,
                snap.whiteElo,
                snap.blackElo,
                1,
                needWdl = false,
            ).firstOrNull() ?: return error(400, "No AI move available")

            synchronized(lock) {
                if (generation.get() != snap.generation) return staleResponse()
                playUciLocked(pred.uci, clearRedo = true)
                generation.incrementAndGet()
            }
            ok(baseState())
        } catch (t: Throwable) {
            error(500, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun undo(): NativeResponse {
        synchronized(lock) {
            if (moveHistory.isNotEmpty()) {
                val undone = moveHistory.removeAt(moveHistory.lastIndex)
                board.undoMove()
                redoHistory.add(undone)
                generation.incrementAndGet()
            }
        }
        return ok(baseState())
    }

    private fun redo(): NativeResponse {
        synchronized(lock) {
            if (redoHistory.isNotEmpty()) {
                val uci = redoHistory.removeAt(redoHistory.lastIndex)
                try {
                    playUciLocked(uci, clearRedo = false)
                    generation.incrementAndGet()
                } catch (_: Throwable) {
                    redoHistory.clear()
                }
            }
        }
        return ok(baseState())
    }

    private fun reset(): NativeResponse {
        synchronized(lock) {
            board = Board().apply { setEnableEvents(false) }
            moveHistory.clear()
            redoHistory.clear()
            generation.incrementAndGet()
        }
        return ok(baseState())
    }

    private fun loadFen(body: JSONObject): NativeResponse {
        val fen = body.optString("fen", "").trim()
        if (fen.isBlank()) return error(400, "FEN is empty")
        return try {
            val loaded = Board().apply {
                setEnableEvents(false)
                loadFromFen(fen)
            }
            synchronized(lock) {
                board = loaded
                moveHistory.clear()
                redoHistory.clear()
                generation.incrementAndGet()
            }
            ok(baseState())
        } catch (t: Throwable) {
            error(400, "Invalid FEN: ${t.message ?: "could not load position"}")
        }
    }

    private fun pv(body: JSONObject): NativeResponse {
        val predictor = engine ?: return error(503, predictorError ?: "Maia model is still loading")
        val snap = snapshot()
        val requested = if (body.has("generation")) body.optInt("generation", -1) else null
        if (requested != null && requested != snap.generation) return staleResponse()
        val count = body.optInt("count", snap.pvCount).coerceIn(1, 4)
        val plies = body.optInt("plies", snap.pvPlies).coerceIn(2, 16)

        return try {
            val rootBoard = boardFromFen(snap.fen)
            val roots = predictor.predict(
                rootBoard,
                snap.history,
                snap.whiteElo,
                snap.blackElo,
                count,
                needWdl = false,
            )
            val lines = JSONArray()

            roots.take(count).forEachIndexed { lineIndex, root ->
                if (generation.get() != snap.generation) return staleResponse()
                val work = boardFromFen(snap.fen)
                val workHistory = snap.history.toMutableList()
                val moves = JSONArray()

                var current: Prediction? = root
                var ply = 1
                while (ply <= plies && current != null) {
                    if (generation.get() != snap.generation) return staleResponse()
                    val side = if (work.sideToMove == Side.WHITE) "white" else "black"
                    moves.put(
                        JSONObject()
                            .put("ply", ply)
                            .put("turn", side)
                            .put("uci", current.uci)
                            .put("san", current.san)
                            .put("probability", current.probability)
                            .put("win", current.win)
                            .put("draw", current.draw)
                            .put("loss", current.loss)
                    )

                    val legal = legalMoveByUci(work, current.uci) ?: break
                    work.doMove(legal)
                    workHistory.add(current.uci)
                    ply++
                    if (ply > plies || work.isMated || work.isStaleMate || work.isDraw) break
                    current = predictor.predict(
                        work,
                        workHistory,
                        snap.whiteElo,
                        snap.blackElo,
                        1,
                        needWdl = false,
                    ).firstOrNull()
                }

                lines.put(
                    JSONObject()
                        .put("rank", lineIndex + 1)
                        .put("first_move_uci", root.uci)
                        .put("first_move_san", root.san)
                        .put("first_probability", root.probability)
                        .put("moves", moves)
                )
            }

            if (generation.get() != snap.generation) return staleResponse()
            ok(
                JSONObject()
                    .put("lines", lines)
                    .put("count", lines.length())
                    .put("plies", plies)
                    .put("human_lines", true)
                    .put("generation", snap.generation)
            )
        } catch (t: Throwable) {
            error(500, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun line(body: JSONObject): NativeResponse {
        val predictor = engine ?: return error(503, predictorError ?: "Maia model is still loading")
        val snap = snapshot()
        val requestedUci = body.optString("uci", "").trim().lowercase()
        val plies = body.optInt("plies", 6).coerceIn(1, 16)
        if (requestedUci.length < 4) return error(400, "Invalid first move")

        return try {
            val work = boardFromFen(snap.fen)
            val workHistory = snap.history.toMutableList()
            val roots = predictor.predict(work, workHistory, snap.whiteElo, snap.blackElo, 20, needWdl = false)
            var current = roots.firstOrNull { it.uci == requestedUci }
                ?: return error(400, "Move is not a legal predicted move")
            val out = JSONArray()
            var ply = 1
            while (ply <= plies) {
                val side = if (work.sideToMove == Side.WHITE) "white" else "black"
                out.put(
                    JSONObject()
                        .put("ply", ply)
                        .put("turn", side)
                        .put("uci", current.uci)
                        .put("san", current.san)
                        .put("probability", current.probability)
                        .put("win", current.win)
                        .put("draw", current.draw)
                        .put("loss", current.loss)
                )
                val legal = legalMoveByUci(work, current.uci) ?: break
                work.doMove(legal)
                workHistory.add(current.uci)
                if (work.isMated || work.isStaleMate || work.isDraw || ++ply > plies) break
                current = predictor.predict(work, workHistory, snap.whiteElo, snap.blackElo, 1, needWdl = false)
                    .firstOrNull() ?: break
            }
            ok(JSONObject().put("line", out).put("plies", out.length()).put("human_line", true))
        } catch (t: Throwable) {
            error(500, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun baseState(): JSONObject = synchronized(lock) {
        val fen = board.fen
        val fenParts = fen.split(" ")
        val halfMove = fenParts.getOrNull(4)?.toIntOrNull() ?: 0
        val fullMove = fenParts.getOrNull(5)?.toIntOrNull() ?: 1

        val pieces = JSONObject()
        for (sq in Square.values()) {
            if (sq == Square.NONE) continue
            val p = board.getPiece(sq)
            if (p != Piece.NONE) pieces.put(sq.toString().lowercase(), p.fenSymbol)
        }

        val legal = JSONArray()
        board.legalMoves().forEach { legal.put(board.toUci(it)) }

        val sans = sanHistory(moveHistory)
        val isMate = board.isMated
        val isStale = board.isStaleMate
        val isDraw = board.isDraw
        val over = isMate || isStale || isDraw
        val result: String? = when {
            isMate && board.sideToMove == Side.WHITE -> "0-1"
            isMate -> "1-0"
            isStale || isDraw -> "1/2-1/2"
            else -> null
        }

        JSONObject()
            .put("fen", fen)
            .put("pieces", pieces)
            .put("turn", if (board.sideToMove == Side.WHITE) "white" else "black")
            .put("fullmove", fullMove)
            .put("halfmove", halfMove)
            .put("check", board.isKingAttacked)
            .put("checkmate", isMate)
            .put("stalemate", isStale)
            .put("game_over", over)
            .put("result", result ?: JSONObject.NULL)
            .put("moves", JSONArray(moveHistory))
            .put("san_moves", JSONArray(sans))
            .put("legal_moves", legal)
            .put("last_move", moveHistory.lastOrNull() ?: JSONObject.NULL)
            .put("can_undo", moveHistory.isNotEmpty())
            .put("can_redo", redoHistory.isNotEmpty())
            .put(
                "settings",
                JSONObject()
                    .put("white_elo", whiteElo)
                    .put("black_elo", blackElo)
                    .put("top_n", topN)
                    .put("pv_count", pvCount)
                    .put("pv_plies", pvPlies)
                    .put("analysis_profile", analysisProfile)
            )
            .put("model_label", "maia3-5m")
            .put("game_mode", gameMode)
            .put("predictor_ready", engine != null)
            .put("predictor_error", predictorError ?: JSONObject.NULL)
            .put("analysis_generation", generation.get())
            .put("cpu_threads", 4)
            .put("predictions", JSONArray())
    }

    private fun sanHistory(history: List<String>): List<String> {
        if (history.isEmpty()) return emptyList()
        return try {
            val list = MoveList()
            val replay = Board().apply { setEnableEvents(false) }
            history.forEach { uci ->
                val m = legalMoveByUci(replay, uci) ?: replay.fromUci(uci)
                list.add(m)
                replay.doMove(m)
            }
            list.toSanArray().toList()
        } catch (_: Throwable) {
            history
        }
    }

    private fun playLocked(from: String, to: String, promotion: String?, clearRedo: Boolean) {
        val raw = from + to + (promotion ?: "")
        var match = legalMoveByUci(board, raw)
        if (match == null && promotion == null) {
            match = legalMoveByUci(board, "${from}${to}q")
        }
        if (match == null) throw IllegalArgumentException("Illegal move")
        val uci = board.toUci(match)
        board.doMove(match)
        moveHistory.add(uci)
        if (clearRedo) redoHistory.clear()
    }

    private fun playUciLocked(uci: String, clearRedo: Boolean) {
        val match = legalMoveByUci(board, uci) ?: throw IllegalArgumentException("Illegal move")
        val canonical = board.toUci(match)
        board.doMove(match)
        moveHistory.add(canonical)
        if (clearRedo) redoHistory.clear()
    }

    private fun legalMoveByUci(b: Board, uci: String): Move? =
        b.legalMoves().firstOrNull { b.toUci(it).equals(uci, ignoreCase = true) }

    private fun predictionJson(p: Prediction): JSONObject = JSONObject()
        .put("uci", p.uci)
        .put("san", p.san)
        .put("probability", p.probability)
        .put("win", p.win)
        .put("draw", p.draw)
        .put("loss", p.loss)

    private fun staleResponse(): NativeResponse = NativeResponse(
        409,
        JSONObject()
            .put("stale", true)
            .put("generation", generation.get())
            .put("predictions", JSONArray())
            .put("lines", JSONArray())
    )

    private fun ok(body: JSONObject) = NativeResponse(200, body)
    private fun error(status: Int, message: String) = NativeResponse(status, JSONObject().put("error", message))

    override fun close() {
        loader.shutdownNow()
        engine?.close()
    }
}
