package com.humanchessai.app

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

internal data class StockfishEval(
    val depth: Int,
    val cpWhite: Int?,
    val mateWhite: Int?,
    val pv: String,
    val bestMove: String,
)

/**
 * Small UCI wrapper around the Stockfish executable bundled as libstockfish.so.
 *
 * Android's native-library directory is executable, unlike arbitrary writable
 * app storage on modern Android versions. The GitHub Actions build therefore
 * places the official Android arm64 Stockfish binary in jniLibs and this class
 * launches it directly from applicationInfo.nativeLibraryDir.
 *
 * Stockfish is deliberately evaluation-only here. Maia remains the engine that
 * chooses human-like moves and continuation lines.
 */
internal class StockfishEngine(context: Context) : Closeable {
    private val process: Process
    private val input: BufferedReader
    private val output: BufferedWriter

    init {
        val engineFile = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
        if (!engineFile.isFile || engineFile.length() <= 0L) {
            error("Bundled Stockfish engine not found: ${engineFile.absolutePath}")
        }

        process = ProcessBuilder(engineFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        input = BufferedReader(InputStreamReader(process.inputStream))
        output = BufferedWriter(OutputStreamWriter(process.outputStream))

        send("uci")
        readUntil("uciok")
        send("setoption name Threads value 2")
        send("setoption name Hash value 64")
        send("setoption name MultiPV value 1")
        send("isready")
        readUntil("readyok")
        send("ucinewgame")
    }

    @Synchronized
    fun evaluate(fen: String, moveTimeMs: Int = 300): StockfishEval {
        check(process.isAlive) { "Stockfish process is not running" }
        val sideIsWhite = fen.split(' ').getOrNull(1) != "b"
        val safeMoveTime = moveTimeMs.coerceIn(75, 3000)

        send("position fen $fen")
        send("go movetime $safeMoveTime")

        var depth = 0
        var cpSide: Int? = null
        var mateSide: Int? = null
        var pv = ""
        var bestMove = ""

        while (true) {
            val line = input.readLine() ?: error("Stockfish terminated unexpectedly")
            if (line.startsWith("info ")) {
                DEPTH.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { depth = it }

                val scoreMatch = SCORE.find(line)
                if (scoreMatch != null) {
                    val kind = scoreMatch.groupValues[1]
                    val value = scoreMatch.groupValues[2].toIntOrNull()
                    if (value != null) {
                        if (kind == "cp") {
                            cpSide = value
                            mateSide = null
                        } else {
                            mateSide = value
                            cpSide = null
                        }
                    }
                }

                val pvIndex = line.indexOf(" pv ")
                if (pvIndex >= 0) pv = line.substring(pvIndex + 4).trim()
            } else if (line.startsWith("bestmove ")) {
                bestMove = line.split(' ').getOrNull(1).orEmpty()
                break
            }
        }

        val sign = if (sideIsWhite) 1 else -1
        return StockfishEval(
            depth = depth,
            cpWhite = cpSide?.times(sign),
            mateWhite = mateSide?.times(sign),
            pv = pv,
            bestMove = bestMove,
        )
    }

    private fun send(command: String) {
        output.write(command)
        output.newLine()
        output.flush()
    }

    private fun readUntil(token: String) {
        while (true) {
            val line = input.readLine() ?: error("Stockfish terminated before $token")
            if (line.trim() == token) return
        }
    }

    @Synchronized
    override fun close() {
        try {
            if (process.isAlive) send("quit")
        } catch (_: Throwable) {
        }
        try {
            input.close()
        } catch (_: Throwable) {
        }
        try {
            output.close()
        } catch (_: Throwable) {
        }
        if (process.isAlive) process.destroy()
    }

    companion object {
        private const val LIB_NAME = "libstockfish.so"
        private val DEPTH = Regex("\\bdepth\\s+(\\d+)")
        private val SCORE = Regex("\\bscore\\s+(cp|mate)\\s+(-?\\d+)")
    }
}
