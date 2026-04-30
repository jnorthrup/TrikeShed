package borg.trikeshed.miniduck

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * JsonProjection: MiniCursor -> NDJSON string.
 *
 * Each MiniRowVec in the cursor is projected to a JSON object.
 * DocRowVec produces {key: value, ...}
 * Other MiniRowVec types are projected via their scalar surface.
 */
fun MiniCursor.toJson(): String {
    val sb = StringBuilder()
    for (i in 0 until this.size) {
        if (i > 0) sb.append('\n')
        sb.append(rowToJson(this.row(i)))
    }
    return sb.toString()
}

fun rowToJson(row: RowVec): String {
    val sb = StringBuilder("[")
    for (i in 0 until row.size) {
        if (i > 0) sb.append(",")
        sb.append(valueToJson(row[i]))
    }
    sb.append(']')
    return sb.toString()
}

fun rowToJson(row: MiniRowVec): String = when (row) {
    is DocRowVec -> {
        val children = row.child
        val sb = StringBuilder("{")
        for (i in 0 until row.size) {
            if (i > 0) sb.append(",")
            val key = row.keys.getOrNull(i) ?: "_$i"
            val cell = row.cells.getOrNull(i)
            val jsonVal = if (cell == null && children != null) {
                val offset = row.size - children.a
                val childIndex = i - offset
                if (childIndex >= 0 && childIndex < children.a) {
                    rowToJson(children.b(childIndex))
                } else {
                    valueToJson(cell)
                }
            } else {
                valueToJson(cell)
            }
            sb.append('"').append(escapeJson(key)).append('"').append(':').append(jsonVal)
        }
        sb.append('}')
        sb.toString()
    }
    is JsonRowVec -> row.rawValue
    else -> {
        val sb = StringBuilder("[")
        for (i in 0 until row.size) {
            if (i > 0) sb.append(",")
            sb.append(valueToJson(row[i]))
        }
        sb.append(']')
        sb.toString()
    }
}

fun valueToJson(v: Any?): String = when (v) {
    null -> "null"
    is String -> '"' + escapeJson(v) + '"'
    is Long, is Int, is Short, is Byte -> v.toString()
    is Double -> if (v.isInfinite() || v.isNaN()) "null" else v.toString()
    is Float -> if (v.isInfinite() || v.isNaN()) "null" else v.toString()
    is Boolean -> v.toString()
    is MiniRowVec -> rowToJson(v)
    else -> '"' + escapeJson(v.toString()) + '"'
}
