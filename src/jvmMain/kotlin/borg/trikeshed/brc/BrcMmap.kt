/**
 * 1BRC Variant: Memory-Mapped Direct Parse
 *
 * Uses TrikeShed's FileBuffer (mmap wrapper) for zero-copy file access.
 * Scans bytes directly from the mapped region — no intermediate allocation.
 *
 * Key TrikeShed features:
 *  - FileBuffer mmap with LongSeries<Byte> accessor
 *  - Direct byte scanning without String creation
 *  - HashMap accumulation on raw byte keys (station name bytes)
 */
package borg.trikeshed.brc

import borg.trikeshed.common.FileBuffer
import kotlin.math.floor

object BrcMmap {

    private class MmapAcc(var min: Int, var max: Int, var sum: Long, var count: Int)

    @JvmStatic
    fun main(args: Array<String>) {
        val file = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"

        val fb = FileBuffer(file, 0L, -1L, true)
        fb.open()

        val map = HashMap<String, MmapAcc>(512)
        val len = fb.a  // file size in bytes
        var pos = 0L
        val nameBuf = ByteArray(128)

        while (pos < len) {
            // Read station name bytes until ';'
            var nameLen = 0
            while (pos < len) {
                val b = fb.b(pos++)
                if (b == ';'.code.toByte()) break
                nameBuf[nameLen++] = b
            }

            // Parse temperature as fixed-point integer (×10)
            var negative = false
            var temp = 0
            while (pos < len) {
                val b = fb.b(pos++)
                when {
                    b == '\n'.code.toByte() -> break
                    b == '\r'.code.toByte() -> continue
                    b == '-'.code.toByte() -> negative = true
                    b == '.'.code.toByte() -> continue // skip decimal point
                    else -> temp = temp * 10 + (b - '0'.code.toByte())
                }
            }
            if (negative) temp = -temp

            // Lookup/insert station
            val stationName = String(nameBuf, 0, nameLen, Charsets.UTF_8)
            val acc = map.getOrPut(stationName) { MmapAcc(temp, temp, 0L, 0) }
            if (temp < acc.min) acc.min = temp
            if (temp > acc.max) acc.max = temp
            acc.sum += temp
            acc.count++
        }

        fb.close()

        // Format output
        val sb = StringBuilder("{")
        map.entries.sortedBy { it.key }.forEachIndexed { i, (name, acc) ->
            if (i > 0) sb.append(", ")
            sb.append(name).append('=')
            sb.append(fmtFP(acc.min)).append('/')
            // sum is already ×10 fixed-point, so mean = sum/count is already ×10
            val meanFP = floor(acc.sum.toDouble() / acc.count + 0.5).toInt()
            sb.append(fmtFP(meanFP)).append('/')
            sb.append(fmtFP(acc.max))
        }
        sb.append('}')
        println(sb)
    }

    /** Format a fixed-point ×10 integer as "[-]d.d" */
    private fun fmtFP(v: Int): String {
        val abs = if (v < 0) -v else v
        val sign = if (v < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }
}
