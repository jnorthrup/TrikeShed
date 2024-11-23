package borg.trikeshed.cursor

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.j
import borg.trikeshed.lib.`↺`
/**
 * Converts a list of CSV strings into a `Cursor` of strings.
 *
 * @param lineList The list of CSV strings, where the first line contains the headers.
 * @return A `Cursor` representing the parsed CSV data.
 */
@OptIn(ExperimentalUnsignedTypes::class)
fun simpleCsvCursor(lineList: List<String>): Cursor {
    // Take the first line as headers and split by ','
    val headerNames = lineList[0].split(",").map { it.trim() }
    val hdrMeta = headerNames.map { RecordMeta(it, IOMemento.IoString) }
    // Count of fields
    val fieldCount = headerNames.size
    val lines = lineList.drop(1)
    val lineSegments = arrayOfNulls<UShortArray>(lines.size)

    return lines.size j { y ->
        val line = lines[y]
        // Lazily create line segments
        val lineSegs = lineSegments[y] ?: UShortArray(headerNames.size).also { proto ->
            lineSegments[y] = proto
            var f = 0
            for ((x, c) in line.withIndex()) if (c == ',') proto[f++] = x.toUShort()
        }

        fieldCount j { x: Int ->
            val start = if (x == 0) 0 else lineSegs[x - 1].toInt() + 1
            val end = if (x == fieldCount - 1) line.length else lineSegs[x].toInt()
            line.substring(start, end) j hdrMeta[x].`↺`
        }
    }
}