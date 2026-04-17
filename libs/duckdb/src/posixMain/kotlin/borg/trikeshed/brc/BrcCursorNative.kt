/**
 * 1BRC Variant: Cursor/Native
 *
 * Uses TrikeShed's Series j operator for lazy construction.
 * Parses measurements into Series of (station, temperature) pairs,
 * then aggregates using HashMap.
 * Entry point: brcCursorNativeMain.
 */
package borg.trikeshed.brc

import borg.trikeshed.common.FileBuffer
import borg.trikeshed.lib.*
import kotlin.math.floor

fun brcCursorNativeMain(args: Array<String>) {
    val file = args.firstOrNull() ?: "measurements.txt"

    val fb = FileBuffer(file, 0L, -1L, true)
    fb.open()

    try {
        // Parse into Series using j operator
        val pairs = parseMeasurements(fb)

        // Aggregate
        val map = HashMap<String, IntArray>(512)
        for (i in 0 until pairs.size) {
            val (station, temp) = pairs[i]
            val acc = map.getOrPut(station) { intArrayOf(temp, temp, 0, 0) }
            if (temp < acc[0]) acc[0] = temp
            if (temp > acc[1]) acc[1] = temp
            acc[2] += temp
            acc[3]++
        }

        // Format output
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
    
    // First pass: count records and store positions
    val nameStarts = IntArray(1000000) { 0 }  // Pre-allocate for up to 1M records
    val tempStarts = IntArray(1000000) { 0 }
    var count = 0
    var pos = 0L
    
    while (pos < len && count < nameStarts.size) {
        nameStarts[count] = pos.toInt()
        
        // Skip to ';'
        while (pos < len && fb.b(pos++) != ';'.code.toByte()) {}
        
        tempStarts[count] = pos.toInt()
        
        // Skip to newline
        while (pos < len) {
            val b = fb.b(pos++)
            if (b == '\n'.code.toByte()) break
        }
        
        count++
    }

    // Build Series using j operator (lazy construction)
    return count j { i: Int ->
        val nameStart = nameStarts[i]
        val tempStart = tempStarts[i]

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
        val station = nameBuf.decodeToString(0, nameLen)

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
