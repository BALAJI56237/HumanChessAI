package com.humanchessai.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp

internal data class Prediction(
    val uci: String,
    val san: String,
    val probability: Double,
    val win: Double,
    val draw: Double,
    val loss: Double,
)

internal class MaiaOnnxEngine(
    private val context: Context,
    val modelName: String = DEFAULT_MODEL,
) : Closeable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions: OrtSession.SessionOptions
    private val session: OrtSession

    private val cache = object : LinkedHashMap<String, List<Prediction>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Prediction>>?): Boolean {
            return size > 256
        }
    }

    init {
        require(modelName in SUPPORTED_MODELS) { "Unsupported Maia model: $modelName" }

        val modelDir = File(context.filesDir, "maia-model").apply { mkdirs() }
        val graphName = "$modelName.onnx"
        val dataName = "$modelName.onnx.data"
        copyAsset("models/$graphName", File(modelDir, graphName))
        copyAsset("models/$dataName", File(modelDir, dataName))

        sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(File(modelDir, graphName).absolutePath, sessionOptions)
    }

    private fun copyAsset(assetPath: String, outFile: File) {
        if (outFile.exists() && outFile.length() > 0L) return
        val tmp = File(outFile.parentFile, outFile.name + ".tmp")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
        }
        if (tmp.length() <= 0L) {
            tmp.delete()
            error("Bundled model asset is empty: $assetPath")
        }
        if (outFile.exists()) outFile.delete()
        if (!tmp.renameTo(outFile)) {
            tmp.copyTo(outFile, overwrite = true)
            tmp.delete()
        }
    }

    @Synchronized
    fun predict(
        board: Board,
        history: List<String>,
        whiteElo: Int,
        blackElo: Int,
        topN: Int,
        needWdl: Boolean = true,
    ): List<Prediction> {
        if (board.isMated() || board.isStaleMate()) return emptyList()
        val n = topN.coerceIn(1, 20)
        val cacheKey = history.joinToString(",") + "|$whiteElo|$blackElo|$n|$needWdl"
        cache[cacheKey]?.let { return it }

        val sideWhite = board.sideToMove == Side.WHITE
        val selfElo = if (sideWhite) whiteElo else blackElo
        val oppoElo = if (sideWhite) blackElo else whiteElo
        val tokens = buildTokens(history)
        val raw = infer(tokens, selfElo, oppoElo)

        val legal = board.legalMoves()
        if (legal.isEmpty()) return emptyList()

        val candidates = legal.mapNotNull { move ->
            val uci = board.toUci(move)
            val modelUci = if (sideWhite) uci else mirrorMove(uci)
            val idx = moveIndex(modelUci)
            if (idx in raw.moveLogits.indices) Triple(move, uci, idx) else null
        }
        if (candidates.isEmpty()) return emptyList()

        val maxLogit = candidates.maxOf { raw.moveLogits[it.third].toDouble() }
        var sum = 0.0
        val weights = DoubleArray(candidates.size)
        for (i in candidates.indices) {
            weights[i] = exp(raw.moveLogits[candidates[i].third].toDouble() - maxLogit)
            sum += weights[i]
        }

        val ranked = candidates.indices
            .sortedByDescending { weights[it] / sum }
            .take(n)

        val out = ArrayList<Prediction>(ranked.size)
        for ((rankIndex, candidateIndex) in ranked.withIndex()) {
            val (move, uci, _) = candidates[candidateIndex]
            val prob = weights[candidateIndex] / sum
            var win = 0.0
            var draw = 1.0
            var loss = 0.0

            if (needWdl && rankIndex == 0) {
                val nextHistory = history + uci
                val nextRaw = infer(buildTokens(nextHistory), oppoElo, selfElo)
                val p = softmax3(nextRaw.valueLogits)
                win = p[0]
                draw = p[1]
                loss = p[2]
            }

            out += Prediction(
                uci = uci,
                san = sanForMove(board, move, uci),
                probability = prob,
                win = win,
                draw = draw,
                loss = loss,
            )
        }
        cache[cacheKey] = out
        return out
    }

    private data class RawOutput(
        val moveLogits: FloatArray,
        val valueLogits: FloatArray,
        val ponder: FloatArray,
    )

    private fun infer(tokens: FloatArray, selfElo: Int, oppoElo: Int): RawOutput {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(tokens), longArrayOf(1, 64, 96)).use { tTokens ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(selfElo.toFloat())), longArrayOf(1)).use { tSelf ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(oppoElo.toFloat())), longArrayOf(1)).use { tOppo ->
                    session.run(
                        mapOf(
                            "tokens" to tTokens,
                            "self_elo" to tSelf,
                            "oppo_elo" to tOppo,
                        )
                    ).use { result ->
                        return RawOutput(
                            moveLogits = firstFloatRow(result[0].value),
                            valueLogits = firstFloatRow(result[1].value),
                            ponder = flatFloat(result[2].value),
                        )
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstFloatRow(value: Any): FloatArray {
        return when (value) {
            is Array<*> -> {
                val first = value.firstOrNull()
                when (first) {
                    is FloatArray -> first
                    is Array<*> -> (first as Array<Float>).map { it }.toFloatArray()
                    else -> error("Unexpected ONNX output row type: ${first?.javaClass}")
                }
            }
            is FloatArray -> value
            else -> error("Unexpected ONNX output type: ${value.javaClass}")
        }
    }

    private fun flatFloat(value: Any): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            val first = value.firstOrNull()
            if (first is FloatArray) first else FloatArray(0)
        }
        else -> FloatArray(0)
    }

    private fun softmax3(x: FloatArray): DoubleArray {
        if (x.size < 3) return doubleArrayOf(0.0, 1.0, 0.0)
        val m = maxOf(x[0], x[1], x[2]).toDouble()
        val a = exp(x[0] - m)
        val b = exp(x[1] - m)
        val c = exp(x[2] - m)
        val s = a + b + c
        return doubleArrayOf(a / s, b / s, c / s)
    }

    private fun sanForMove(board: Board, move: Move, fallback: String): String {
        return try {
            val list = MoveList(board.fen)
            list.add(move)
            list.toSanArray().firstOrNull() ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun buildTokens(history: List<String>): FloatArray {
        val replay = Board()
        replay.setEnableEvents(false)
        val states = ArrayList<FloatArray>()
        states += tokenizeBoard(replay)

        for (uci in history) {
            val move = replay.fromUci(uci)
            val legal = replay.legalMoves().firstOrNull { replay.toUci(it) == uci } ?: move
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
            val tokenSide = if (!blackToMove) originalSide else if (originalSide == Side.WHITE) Side.BLACK else Side.WHITE
            val channel = (mapped - 1) + if (tokenSide == Side.BLACK) 6 else 0
            out[targetIndex * 12 + channel] = 1f
        }
        return out
    }

    private fun moveIndex(uci: String): Int {
        if (uci.length == 5) {
            val fromFile = uci[0] - 'a'
            val toFile = uci[2] - 'a'
            val piece = when (uci[4].lowercaseChar()) {
                'q' -> 0
                'r' -> 1
                'b' -> 2
                'n' -> 3
                else -> return -1
            }
            return 4096 + ((fromFile * 8 + toFile) * 4 + piece)
        }
        if (uci.length < 4) return -1
        return squareIndex(uci.substring(0, 2)) * 64 + squareIndex(uci.substring(2, 4))
    }

    private fun squareIndex(square: String): Int {
        val file = square[0] - 'a'
        val rank = square[1] - '1'
        return rank * 8 + file
    }

    private fun mirrorMove(uci: String): String {
        if (uci.length < 4) return uci
        fun mirrorSquare(s: String): String = "${s[0]}${'0' + (9 - (s[1] - '0'))}"
        val suffix = if (uci.length > 4) uci.substring(4) else ""
        return mirrorSquare(uci.substring(0, 2)) + mirrorSquare(uci.substring(2, 4)) + suffix
    }

    @Synchronized
    override fun close() {
        session.close()
        sessionOptions.close()
    }

    companion object {
        const val DEFAULT_MODEL = "maia3-5m"
        val SUPPORTED_MODELS = setOf("maia3-5m", "maia3-23m", "maia3-79m")
    }
}
