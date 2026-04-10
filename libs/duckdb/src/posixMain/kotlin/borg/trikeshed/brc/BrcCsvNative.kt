/**
 * 1BRC Variant: CSV/Native
 *
 * Uses FileBuffer (mmap) for zero-copy byte scanning.
 * Accumulates fixed-point ×10 integers per station.
 * Entry point: brcCsvNativeMain (registered as native executable).
 */
package borg.trikeshed.brc

import borg.trikeshed.common.FileBuffer
import kotlin.math.floor

fun brcCsvNativeMain(args: Array<String>) {
    val file = args.firstOrNull() ?: "measurements.txt"

    val fb = FileBuffer(file, 0L, -1L, true)
    fb.open()

    // acc[0]=min×10, acc[1]=max×10, acc[2]=sum×10, acc[3]=count
    val map = HashMap<String, IntArray>(512)
    val len = fb.a          // file size (Long)
    var pos = 0L
    val nameBuf = ByteArray(128)

    while (pos < len) {
        // Read station name until ';'
        var nameLen = 0
        while (pos < len) {
            val b = fb.b(pos++)
            if (b == ';'.code.toByte()) break
            nameBuf[nameLen++] = b
        }
        if (nameLen == 0) continue

        // Parse temperature as fixed-point ×10
        var negative = false
        var temp = 0
        while (pos < len) {
            val b = fb.b(pos++)
            when {
                b == '\n'.code.toByte() -> break
                b == '\r'.code.toByte() -> continue
                b == '-'.code.toByte()  -> negative = true
                b == '.'.code.toByte()  -> continue
                else -> temp = temp * 10 + (b - '0'.code.toByte())
            }
        }
        if (negative) temp = -temp

        val station = nameBuf.decodeToString(0, nameLen)
        val acc = map.getOrPut(station) { intArrayOf(temp, temp, 0, 0) }
        if (temp < acc[0]) acc[0] = temp
        if (temp > acc[1]) acc[1] = temp
        acc[2] += temp
        acc[3]++
    }

    fb.close()

    val sb = StringBuilder("{")
    map.entries.sortedBy { it.key }.forEachIndexed { i, (name, acc) ->
        if (i > 0) sb.append(", ")
        sb.append(name).append('=')
        sb.append(fmtFP(acc[0])).append('/')
        val meanFP = floor(acc[2].toDouble() / acc[3] + 0.5).toInt()
        sb.append(fmtFP(meanFP)).append('/')
        sb.append(fmtFP(acc[1]))
    }
    sb.append('}')
    println(sb)
}

private fun fmtFP(v: Int): String {
    val abs = if (v < 0) -v else v
    val sign = if (v < 0) "-" else ""
    return "${sign}${abs / 10}.${abs % 10}"
}
