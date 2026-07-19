@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.parse.confix

import borg.trikeshed.charstr.CharStr
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.mutable.*

interface ConfixLifecycle
typealias ConfixIndex = FacetedRow<Any>

enum class Syntax {
    JSON {
        override fun scan(src: Series<Byte>): Cursor = scan0(src).a
        override fun recognize(first: Byte): Boolean = first.toInt().toChar() in setOf('{', '[', '"')
    },
    CBOR {
        override fun scan(src: Series<Byte>): Cursor = scanCbor0(src).a
        override fun recognize(first: Byte): Boolean = true
    },
    YAML {
        override fun scan(src: Series<Byte>): Cursor = scan0(src).a
        override fun recognize(first: Byte): Boolean = first.toInt().toChar() !in setOf('{', '[')
    };

    abstract fun scan(src: Series<Byte>): Cursor
    abstract fun recognize(first: Byte): Boolean

    fun dispatch(bytes: ByteArray): Cursor {
        val source: Series<Byte> = bytes.size j { bytes[it] }
        return entries.first { it.recognize(source[0]) }.scan(source)
    }

    fun decodeText(src: Series<Char>, open: Int, close: Int): CharStr {
        val first = src[open]
        val last = src[close]
        if (first == '"' && last == '"' && close > open + 1) return CharStr(src, open + 1, close - 1)
        return CharStr(src, open, close)
    }

    fun decodeValue(src: Series<Char>, open: Int, close: Int, tag: IOMemento): Any? = when (tag) {
        IOMemento.IoString -> decodeText(src, open, close)
        IOMemento.IoBoolean -> src[open] == 't'
        IOMemento.IoNothing -> null
        IOMemento.IoDouble -> decodeText(src, open, close)
        else -> null
    }

    val COL_META: Series<`ColumnMeta↻`> = 4 j { column ->
        when (column) {
            0 -> ColumnMeta("open", IOMemento.IoInt)
            1 -> ColumnMeta("close", IOMemento.IoInt)
            2 -> ColumnMeta("tag", IOMemento.IoObject)
            3 -> ColumnMeta("children", IOMemento.IoObject)
            else -> error("4")
        }.let { { it } }
    }

    data class FlatIndex(
        val spans: Series<Twin<Int>>,
        val tags: Series<IOMemento>,
        val depths: Series<Int>,
        val childOf: (Int) -> Series<Int>,
    )

    fun scan0(src: Series<Byte>): Join<Cursor, FlatIndex> {
        val chars: Series<Char> = src.size j { src[it].toInt().toChar() }
        val opens = series()
        val closes = series()
        val tags = ChunkedMutableSeries<IOMemento>()
        fun add(open: Int, close: Int, tag: IOMemento) {
            opens.add(open)
            closes.add(close)
            tags.add(tag)
        }
        data class Pending(val open: Int, val tag: IOMemento)
        val stack = ChunkedMutableSeries<Pending>()
        var inQuote = false
        var escaped = false
        fun push(open: Int, tag: IOMemento) = stack.add(Pending(open, tag))
        fun pop(close: Int) {
            if (stack.size == 0) return
            val pending = stack.removeAt(stack.size - 1)
            add(pending.open, close, pending.tag)
        }
        var index = 0
        while (index < src.size) {
            val char = chars[index]
            when {
                inQuote -> when {
                    escaped -> {
                        escaped = false
                        if (char == '"') {
                            inQuote = false
                            pop(index)
                        }
                    }
                    char == '\\' -> escaped = true
                    char == '"' -> {
                        inQuote = false
                        pop(index)
                    }
                }
                else -> when (char) {
                    '{' -> push(index, IOMemento.IoObject)
                    '[' -> push(index, IOMemento.IoArray)
                    '}', ']' -> pop(index)
                    '"' -> {
                        push(index, IOMemento.IoString)
                        inQuote = true
                    }
                    't' -> if (index + 3 < src.size && chars[index + 1] == 'r' && chars[index + 2] == 'u' && chars[index + 3] == 'e') {
                        add(index, index + 3, IOMemento.IoBoolean)
                        index += 3
                    }
                    'f' -> if (index + 4 < src.size && chars[index + 1] == 'a' && chars[index + 2] == 'l' && chars[index + 3] == 's' && chars[index + 4] == 'e') {
                        add(index, index + 4, IOMemento.IoBoolean)
                        index += 4
                    }
                    'n' -> if (index + 3 < src.size && chars[index + 1] == 'u' && chars[index + 2] == 'l' && chars[index + 3] == 'l') {
                        add(index, index + 3, IOMemento.IoNothing)
                        index += 3
                    }
                    '-', '+', in '0'..'9' -> {
                        val start = index
                        while (index < src.size) {
                            val next = chars[index]
                            if (next !in '0'..'9' && next != '.' && next != 'e' && next != 'E' && next != '+' && next != '-') break
                            index++
                        }
                        add(start, index - 1, IOMemento.IoDouble)
                        continue
                    }
                }
            }
            index++
        }
        while (stack.size > 0) {
            val pending = stack.removeAt(stack.size - 1)
            add(pending.open, src.size - 1, pending.tag)
        }
        return buildTree(opens, closes, tags)
    }

    fun scanCbor0(src: Series<Byte>): Join<Cursor, FlatIndex> {
        val opens = series()
        val closes = series()
        val tags = ChunkedMutableSeries<IOMemento>()
        fun add(open: Int, close: Int, tag: IOMemento) {
            opens.add(open)
            closes.add(close)
            tags.add(tag)
        }
        fun readLength(position: Int, additionalInfo: Int): Pair<Long, Int> = when (additionalInfo) {
            in 0..23 -> additionalInfo.toLong() to position
            24 -> (src[position].toLong() and 0xFF) to (position + 1)
            25 -> (((src[position].toInt() and 0xFF) shl 8) or (src[position + 1].toInt() and 0xFF)).toLong() to (position + 2)
            26 -> (((src[position].toInt() and 0xFF) shl 24) or ((src[position + 1].toInt() and 0xFF) shl 16) or ((src[position + 2].toInt() and 0xFF) shl 8) or (src[position + 3].toInt() and 0xFF)).toLong() to (position + 4)
            27 -> {
                var value = 0L
                var offset = 0
                while (offset < 8) {
                    value = (value shl 8) or (src[position + offset].toLong() and 0xFF)
                    offset++
                }
                value to (position + 8)
            }
            31 -> -1L to position
            else -> error("cbor ai $additionalInfo")
        }
        fun parseItem(position: Int): Int {
            val open = position
            val initialByte = src[position].toInt() and 0xFF
            val majorType = initialByte ushr 5
            val additionalInfo = initialByte and 0x1F
            return when (majorType) {
                0, 1 -> {
                    val (_, next) = readLength(position + 1, additionalInfo)
                    add(open, next - 1, IOMemento.IoLong)
                    next
                }
                2 -> {
                    val (length, next) = readLength(position + 1, additionalInfo)
                    if (length < 0) next else {
                        add(open, next + length.toInt() - 1, IOMemento.IoBytes)
                        next + length.toInt()
                    }
                }
                3 -> {
                    val (length, next) = readLength(position + 1, additionalInfo)
                    if (length < 0) next else {
                        add(open, next + length.toInt() - 1, IOMemento.IoString)
                        next + length.toInt()
                    }
                }
                4, 5 -> {
                    val (length, next) = readLength(position + 1, additionalInfo)
                    var cursor = next
                    if (length < 0L) {
                        while (cursor < src.size && (src[cursor].toInt() and 0xFF) != 0xFF) {
                            cursor = parseItem(cursor)
                            if (majorType == 5) cursor = parseItem(cursor)
                        }
                    } else {
                        repeat(if (majorType == 5) length.toInt() * 2 else length.toInt()) {
                            cursor = parseItem(cursor)
                        }
                    }
                    if (cursor < src.size && length < 0L) cursor++
                    add(open, cursor - 1, if (majorType == 4) IOMemento.IoArray else IOMemento.IoObject)
                    cursor
                }
                6 -> {
                    val (_, next) = readLength(position + 1, additionalInfo)
                    parseItem(next)
                }
                7 -> {
                    val tag = when (additionalInfo) {
                        20, 21 -> IOMemento.IoBoolean
                        22, 23 -> IOMemento.IoNothing
                        25, 26, 27 -> IOMemento.IoDouble
                        else -> IOMemento.IoNothing
                    }
                    val size = when (additionalInfo) {
                        25 -> 2
                        26 -> 4
                        27 -> 8
                        24 -> 1
                        else -> 0
                    }
                    add(open, open + size, tag)
                    position + 1 + size
                }
                else -> {
                    add(open, open, IOMemento.IoNothing)
                    position + 1
                }
            }
        }
        var position = 0
        while (position < src.size) position = parseItem(position)
        return buildTree(opens, closes, tags)
    }

    fun buildTree(
        opens: ChunkedMutableSeries<Int>,
        closes: ChunkedMutableSeries<Int>,
        rawTags: ChunkedMutableSeries<IOMemento>,
    ): Join<Cursor, FlatIndex> {
        val total = opens.size
        val sourceOrder = (0 until total).sortedBy { opens[it] }
        val spans: Series<Twin<Int>> = total j { index: Int -> opens[sourceOrder[index]] j closes[sourceOrder[index]] }
        val tags: Series<IOMemento> = total j { index: Int -> rawTags[sourceOrder[index]] }
        val depths: Series<Int> = total j { index: Int ->
            val span = spans[index]
            (0 until total).count { other -> other != index && spans[other].a < span.a && spans[other].b >= span.b }
        }
        val childOf: (Int) -> Series<Int> = { parent: Int ->
            val parentSpan = spans[parent]
            val childDepth = depths[parent] + 1
            val children = IntArray(total)
            var count = 0
            for (candidate in 0 until total) {
                if (candidate == parent) continue
                val span = spans[candidate]
                if (span.a > parentSpan.a && span.b <= parentSpan.b && depths[candidate] == childDepth) children[count++] = candidate
            }
            count j { childIndex: Int -> children[childIndex] }
        }
        val rowCache = arrayOfNulls<RowVec>(total)
        fun row(index: Int): RowVec {
            rowCache[index]?.let { return it }
            val span = spans[index]
            val children = childOf(index)
            val cursor: Cursor = children.size j { childIndex: Int -> row(children[childIndex]) }
            val row = (4 j { column: Int ->
                when (column) {
                    0 -> (span.a as Any?) j COL_META[0]
                    1 -> (span.b as Any?) j COL_META[1]
                    2 -> (tags[index] as Any?) j COL_META[2]
                    3 -> (cursor as Any?) j COL_META[3]
                    else -> error("4")
                }
            }) as RowVec
            rowCache[index] = row
            return row
        }
        val roots = (0 until total).filter { depths[it] == 0 }
        return (roots.size j { rootIndex: Int -> row(roots[rootIndex]) }) j FlatIndex(spans, tags, depths, childOf)
    }

    fun series(): ChunkedMutableSeries<Int> = ChunkedMutableSeries()

    fun scanIndex(src: Series<Byte>): ConfixIndex {
        val (tree, flat) = when (this) {
            CBOR -> scanCbor0(src)
            else -> scan0(src)
        }
        val keys = LinkedHashMap<String, Int>()
        for (index in 0 until flat.spans.size) {
            if (flat.tags[index] != IOMemento.IoString) continue
            val span = flat.spans[index]
            val key = if (this == CBOR) {
                decodeCborText(src, span.a) ?: continue
            } else {
                val open = span.a + 1
                val close = span.b - 1
                if (close < open) continue
                CharArray(close - open + 1) { offset -> src[open + offset].toInt().toChar() }.concatToString()
            }
            if (key !in keys) keys[key] = index
        }
        return flat.spans.size j { operation: Any? ->
            when (operation) {
                ConfixIndexK.Spans -> flat.spans
                ConfixIndexK.Tags -> flat.tags
                ConfixIndexK.Depths -> flat.depths
                ConfixIndexK.DirectChildren -> flat.childOf
                ConfixIndexK.TreeCursor -> tree
                ConfixIndexK.KeyToChild -> ({ key: CharSequence -> keys[key.toString()] })
                else -> null
            }
        }
    }
}
