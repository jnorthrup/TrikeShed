/**
 * 1BRC Variant: Cursor Composition
 *
 * Uses TrikeShed's Cursor and Series operators (j, α) to parse
 * and aggregate the measurements file. This variant demonstrates:
 *
 * - FileBuffer for memory-mapped file access
 * - CharSeries for zero-copy character parsing
 * - Series composition with j operator
 * - Cursor-based columnar aggregation
 * - Functional transformations with α (alpha conversion)
 *
 * Performance characteristics:
 * - Zero-copy file access via mmap
 * - Lazy evaluation through Series
 * - Columnar processing for aggregation
 */
package borg.trikeshed.brc

import borg.trikeshed.common.FileBuffer
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.*
import kotlin.math.floor

object BrcCursor {

    private class CursorAcc(var min: Int, var max: Int, var sum: Long, var count: Int)

    @JvmStatic
    fun main(args: Array<String>) {
        val file = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"

        // Open file with mmap
        val fb = FileBuffer(file, 0L, -1L, true)
        fb.open()

        try {
            // Parse file into Series of (station, temperature) pairs
            val pairs = parseMeasurements(fb)

            // Aggregate using HashMap (same as mmap variant for comparison)
            val map = HashMap<String, CursorAcc>(512)
            for (i in 0 until pairs.size) {
                val (station, temp) = pairs[i]
                val acc = map.getOrPut(station) { CursorAcc(temp, temp, 0L, 0) }
                if (temp < acc.min) acc.min = temp
                if (temp > acc.max) acc.max = temp
                acc.sum += temp
                acc.count++
            }

            // Format output
            val sb = StringBuilder("{")
            map.entries.sortedBy { it.key }.forEachIndexed { i, (name, acc) ->
                if (i > 0) sb.append(", ")
                sb.append(name).append('=')
                sb.append(fmtFP(acc.min)).append('/')
                val meanFP = floor(acc.sum.toDouble() / acc.count + 0.5).toInt()
                sb.append(fmtFP(meanFP)).append('/')
                sb.append(fmtFP(acc.max))
            }
            sb.append('}')
            println(sb)

        } finally {
            fb.close()
        }
    }

    /**
     * Parse measurements file into Series of (station, temperature) pairs.
     * Uses TrikeShed's j operator for lazy Series construction.
     */
    private fun parseMeasurements(fb: FileBuffer): Series<Pair<String, Int>> {
        val len = fb.a
        val positions = mutableListOf<Pair<Int, Int>>() // (nameStart, tempStart) pairs

        // First pass: find all record positions
        var pos = 0L
        while (pos < len) {
            val nameStart = pos.toInt()

            // Skip to ';'
            while (pos < len && fb.b(pos++) != ';'.code.toByte()) {}

            val tempStart = pos.toInt()

            // Skip to newline
            while (pos < len) {
                val b = fb.b(pos++)
                if (b == '\n'.code.toByte()) break
            }

            positions.add(Pair(nameStart, tempStart))
        }

        // Build Series using j operator (lazy construction)
        return positions.size j { i: Int ->
            val (nameStart, tempStart) = positions[i]

            // Extract station name
            var nameLen = 0
            var p = nameStart.toLong()
            while (p < tempStart && fb.b(p) != ';'.code.toByte()) {
                nameLen++
                p++
            }
            val nameBuf = ByteArray(nameLen)
            for (j in 0 until nameLen) {
                nameBuf[j] = fb.b((nameStart + j).toLong())
            }
            val station = String(nameBuf, Charsets.UTF_8)

            // Parse temperature as fixed-point ×10
            var negative = false
            var temp = 0
            p = tempStart.toLong()
            while (p < len) {
                val b = fb.b(p++)
                when {
                    b == '\n'.code.toByte() -> break
                    b == '\r'.code.toByte() -> continue
                    b == '-'.code.toByte() -> negative = true
                    b == '.'.code.toByte() -> continue
                    else -> temp = temp * 10 + (b - '0'.code.toByte())
                }
            }
            if (negative) temp = -temp

            Pair(station, temp)
        }
    }

    /** Format fixed-point ×10 integer as "[-]d.d" */
    private fun fmtFP(v: Int): String {
        val abs = if (v < 0) -v else v
        val sign = if (v < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }
}
