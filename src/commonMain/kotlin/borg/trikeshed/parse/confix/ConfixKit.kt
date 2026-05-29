@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")
package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.isam.meta.IOMemento

// ── Thread-local mutable factories ────────────────────────────────

private val srcTL  = ThreadLocal<Series<Byte>?>()
private val idxTL  = ThreadLocal<ConfixIndex?>()
private val curTL  = ThreadLocal<Cursor?>()

var src: Series<Byte>?
    get() = srcTL.get()
    set(v) { srcTL.set(v) }

var idx: ConfixIndex?
    get() = idxTL.get()
    set(v) { idxTL.set(v) }

var cursor: Cursor?
    get() = curTL.get()
    set(v) { curTL.set(v) }

// ── confix factories ───────────────────────────────────────────────

fun confix(bytes: ByteArray, syntax: Syntax = Syntax.JSON): Cursor {
    val s: Series<Byte> = bytes.size j { i -> bytes[i] }
    val i = syntax.scanIndex(s)
    src = s; idx = i
    @Suppress("UNCHECKED_CAST")
    return i.b(ConfixIndexK.TreeCursor) as Cursor
}

fun confix(text: String): Cursor {
    val first = text.trimStart().firstOrNull()
    val syntax = if (first == '{' || first == '[' || first == '"') Syntax.JSON else Syntax.YAML
    return confix(text.encodeToByteArray(), syntax)
}

// ── reify from thread-local ────────────────────────────────────────

fun reify(tokenIdx: Int): Any? {
    val s = src ?: return null
    val c = cursor ?: return null
    if (tokenIdx < 0 || tokenIdx >= c.size) return null
    val row = c[tokenIdx]
    // row is Series<Join<A,ColumnMeta↻>>, row[col] = Join<Any?,ColumnMeta↻>, .a = value
    val open  = (row[0] as Join<*, *>).a as Int
    val close = (row[1] as Join<*, *>).a as Int
    val tag   = (row[2] as Join<*, *>).a as IOMemento
    return when (tag) {
        IOMemento.IoNothing   -> null
        IOMemento.IoBoolean  -> s[open].toInt().toChar() == 't'
        IOMemento.IoDouble    -> s.str(open, close).toDoubleOrNull()
        IOMemento.IoInt      -> s.num(open, close).toInt()
        IOMemento.IoLong       -> s.num(open, close)
        IOMemento.IoString   -> s.str(open + 1, close - 1)
        IOMemento.IoByteArray-> ByteArray(close - open + 1) { s[open + it] }
        IOMemento.IoObject,
        IOMemento.IoArray    -> (row[3] as Join<*, *>).a
        else                 -> s.str(open, close)
    }
}

// ── span helpers ───────────────────────────────────────────────────

private fun Series<Byte>.str(open: Int, close: Int): String {
    if (close < open) return ""
    return CharArray(close - open + 1) { this[open + it].toInt().toChar() }.concatToString()
}

private fun Series<Byte>.num(open: Int, close: Int): Long {
    var v = 0L; var neg = false; var i = open
    if (i <= close && this[i].toInt().toChar() == '-') { neg = true; i++ }
    while (i <= close) { v = v * 10 + (this[i++].toInt().toChar() - '0') }
    return if (neg) -v else v
}