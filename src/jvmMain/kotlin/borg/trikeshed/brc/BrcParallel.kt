/**
 * 1BRC Variant: Parallel Chunked MMap
 *
 * Splits the mmap'd file into chunks aligned on newline boundaries,
 * processes each chunk with a coroutine, then merges results.
 *
 * Key TrikeShed features:
 *  - FileBuffer mmap for zero-copy access
 *  - kotlinx.coroutines for structured concurrency
 *  - Series `j` operator for building chunk descriptors
 *  - Merge-reduce pattern across chunk results
 */
package borg.trikeshed.lib.brc

import borg.trikeshed.common.FileBuffer
import kotlinx.coroutines.*
import kotlin.math.floor
import kotlin.math.min

object BrcParallel {

    private class ChunkAcc(var min: Int, var max: Int, var sum: Long, var count: Int) {
        fun merge(temp: Int) {
            if (temp < min) min = temp
            if (temp > max) max = temp
            sum += temp
            count++
        }

        fun merge(other: ChunkAcc) {
            if (other.min < min) min = other.min
            if (other.max > max) max = other.max
            sum += other.sum
            count += other.count
        }
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking(Dispatchers.Default) {
        val file = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"
        val numChunks = Runtime.getRuntime().availableProcessors()

        val fb = FileBuffer(file, 0L, -1L, true)
        fb.open()
        val fileSize = fb.a

        // Compute chunk boundaries aligned to newlines
        val chunkBounds = LongArray(numChunks + 1)
        chunkBounds[0] = 0L
        chunkBounds[numChunks] = fileSize

        for (i in 1 until numChunks) {
            var pos = fileSize * i / numChunks
            // Advance to next newline
            while (pos < fileSize && fb.b(pos) != '\n'.code.toByte()) pos++
            if (pos < fileSize) pos++ // skip the newline
            chunkBounds[i] = pos
        }

        // Process each chunk in a coroutine
        val chunkResults = (0 until numChunks).map { idx ->
            async {
                processChunk(fb, chunkBounds[idx], chunkBounds[idx + 1])
            }
        }

        // Merge all chunk results
        val merged = HashMap<String, ChunkAcc>(512)
        for (deferred in chunkResults) {
            val chunkMap = deferred.await()
            for ((name, acc) in chunkMap) {
                val existing = merged[name]
                if (existing != null) existing.merge(acc)
                else merged[name] = acc
            }
        }

        fb.close()

        // Format output
        val sb = StringBuilder("{")
        merged.entries.sortedBy { it.key }.forEachIndexed { i, (name, acc) ->
            if (i > 0) sb.append(", ")
            sb.append(name).append('=')
            sb.append(fmtFP(acc.min)).append('/')
            sb.append(fmtMean(acc.sum, acc.count)).append('/')
            sb.append(fmtFP(acc.max))
        }
        sb.append('}')
        println(sb)
    }

    private fun processChunk(fb: FileBuffer, start: Long, end: Long): HashMap<String, ChunkAcc> {
        val map = HashMap<String, ChunkAcc>(512)
        val nameBuf = ByteArray(128)
        var pos = start

        while (pos < end) {
            // Read station name
            var nameLen = 0
            while (pos < end) {
                val b = fb.b(pos++)
                if (b == ';'.code.toByte()) break
                nameBuf[nameLen++] = b
            }

            // Parse temperature as fixed-point ×10
            var negative = false
            var temp = 0
            while (pos < end) {
                val b = fb.b(pos++)
                when {
                    b == '\n'.code.toByte() -> break
                    b == '\r'.code.toByte() -> continue
                    b == '-'.code.toByte() -> negative = true
                    b == '.'.code.toByte() -> continue
                    else -> temp = temp * 10 + (b - '0'.code.toByte())
                }
            }
            if (negative) temp = -temp

            val stationName = String(nameBuf, 0, nameLen, Charsets.UTF_8)
            val acc = map.getOrPut(stationName) { ChunkAcc(temp, temp, 0L, 0) }
            acc.merge(temp)
        }

        return map
    }

    private fun fmtFP(v: Int): String {
        val abs = if (v < 0) -v else v
        val sign = if (v < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }

    private fun fmtMean(sum: Long, count: Int): String {
        // sum is already ×10 fixed-point, so mean = sum/count is already ×10
        val mean = sum.toDouble() / count
        val rounded = floor(mean + 0.5).toLong()
        val abs = if (rounded < 0) -rounded else rounded
        val sign = if (rounded < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }
}
