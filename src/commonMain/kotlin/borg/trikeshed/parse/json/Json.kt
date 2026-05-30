@file:Suppress("UNCHECKED_CAST")
package borg.trikeshed.parse.json

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import borg.trikeshed.parse.confix.*

/**
 * Thin re-export layer over the confix JSON stack.
 *
 * Backward-compatible API: callers use JsonParser.parse(text) — engine now backed by confix.
 */

object JsonParser {
    /** Parse JSON text into a Cursor (tree of RowVec). */
    fun scan(text: String): Cursor = scan(text.toSeries())

    /** Parse JSON Series<Char> into a Cursor. */
    fun scan(src: Series<Char>): Cursor {
        val bytes = src.encodeToByteArray()
        return Syntax.JSON.scan(bytes.size j { i -> bytes[i] })
    }

    /** Parse JSON text into a stdlib Map. */
    fun parse(text: String): Map<String, Any?> = parse(text.toSeries())

    /** Parse JSON Series<Char> into a stdlib Map. */
    fun parse(src: Series<Char>): Map<String, Any?> {
        val bytes = src.encodeToByteArray()
        val series: Series<Byte> = bytes.size j { i -> bytes[i] }
        val cursor = Syntax.JSON.scan(series)
        return cursorToMap(cursor, src)
    }
}

/** Convert a 1-row Cursor (object) into a LinkedHashMap using the source chars for keys. */
private fun cursorToMap(cursor: Cursor, src: Series<Char>): LinkedHashMap<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    if (cursor.size == 0) return map
    val row = cursor[0]
    // row is RowVec = Series2<Any?, ColumnMeta↻>
    // columns: 0=open, 1=close, 2=tag, 3=children
    val open = row[0].a as? Int ?: return map
    val close = row[0].b as? Int ?: return map
    val children = row[3].a as? Cursor ?: return map
    // Object children alternate key/value
    var i = 0
    while (i < children.size - 1) {
        val keyRow = children[i]
        val valRow = children[i + 1]
        val kOpen = keyRow[0].a as? Int ?: break
        val kClose = keyRow[0].b as? Int ?: break
        val key = src[kOpen..kClose].asString()
        val value = materializeRow(valRow, src)
        map[key] = value
        i += 2
    }
    return map
}

private fun materializeRow(row: RowVec, src: Series<Char>): Any? {
    val tag = row[2].a as? IOMemento ?: return null
    val open = row[0].a as? Int ?: return null
    val close = row[0].b as? Int ?: return null
    return when (tag) {
        IOMemento.IoString -> src[open..close].asString()
        IOMemento.IoBoolean -> src[open] == 't'
        IOMemento.IoDouble -> src[open..close].asString().toDouble()
        IOMemento.IoNothing -> null
        IOMemento.IoObject -> {
            val children = row[3].a as? Cursor ?: return null
            cursorToMap(children.size j { children[it] }, src)
        }
        IOMemento.IoArray -> {
            val children = row[3].a as? Cursor ?: return emptyList<Any?>()
            val list = ArrayList<Any?>(children.size)
            for (i in 0 until children.size) list.add(materializeRow(children[i], src))
            list
        }
        else -> src[open..close].asString()
    }
}

/** Parse a JSON string to a stdlib Map (convenience) */
fun parse(text: String): Map<String, Any?> = JsonParser.parse(text)

private fun Series<Char>.asString(): String {
    val sb = StringBuilder(size)
    for (i in 0 until size) sb.append(this[i])
    return sb.toString()
}
