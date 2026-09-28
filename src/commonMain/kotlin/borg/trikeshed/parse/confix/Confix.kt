@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.parse.confix

import borg.trikeshed.charstr.CharStr
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.collections.*
import borg.trikeshed.job.ContentId

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
        override fun scan(src: Series<Byte>): Cursor = scanYaml0(src).a
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
        // One read of the source into a primitive array; the scan below indexes it with no boxed calls.
        val chars = CharArray(src.size) { src[it].toInt().toChar() }
        val opens = series()
        val closes = series()
        val tags = ChunkedMutableSeries<IOMemento>()
        fun add(open: Int, close: Int, tag: IOMemento) {
            opens.add(open)
            closes.add(close)
            tags.add(tag)
        }
        // Pending opens as parallel primitive stacks: the open offset and its tag ordinal.
        var stackOpen = IntArray(64); var stackTag = IntArray(64); var depth = 0
        var inQuote = false
        var escaped = false
        fun push(open: Int, tag: IOMemento) {
            if (depth == stackOpen.size) { stackOpen = stackOpen.copyOf(depth * 2); stackTag = stackTag.copyOf(depth * 2) }
            stackOpen[depth] = open; stackTag[depth] = tag.ordinal; depth++
        }
        fun pop(close: Int) {
            if (depth == 0) return
            depth--
            add(stackOpen[depth], close, IOMemento.entries[stackTag[depth]])
        }
        var index = 0
        while (index < src.size) {
            val char = chars[index]
            when {
                inQuote -> when {
                    // Delta 2026-09-05: an escaped character never closes the string — `\"` is a
                    // quote INSIDE the text. This branch used to close on it, so every JSON string
                    // carrying a quoted phrase (narsese receipts: «"…"») ended early and the rest
                    // of the document indexed as garbage keys and Infinity-valued numbers.
                    escaped -> escaped = false
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
        while (depth > 0) pop(src.size - 1)
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
        opens: PackedIntBuf,
        closes: PackedIntBuf,
        rawTags: ChunkedMutableSeries<IOMemento>,
    ): Join<Cursor, FlatIndex> {
        val total = opens.size
        // Source order: packed (open shl 32) or scan slot, sorted — equal opens keep the scanner's order.
        val order = LongArray(total) { (opens[it].toLong() shl 32) or it.toLong() }
        order.sort()
        val open = IntArray(total); val close = IntArray(total)
        val tag = arrayOfNulls<IOMemento>(total)
        for (k in 0 until total) {
            val slot = order[k].toInt()
            open[k] = opens[slot]; close[k] = closes[slot]; tag[k] = rawTags[slot]
        }
        // One stack sweep: a span's parent is the innermost open span that still covers it.
        val depth = IntArray(total); val parent = IntArray(total)
        val stack = IntArray(total); var top = 0
        val start = IntArray(total + 1)
        var rootCount = 0
        for (k in 0 until total) {
            while (top > 0 && close[stack[top - 1]] < close[k]) top--
            parent[k] = if (top > 0) stack[top - 1] else -1
            depth[k] = top
            if (top > 0) start[parent[k] + 1]++ else rootCount++
            stack[top++] = k
        }
        for (k in 0 until total) start[k + 1] += start[k]
        val kid = IntArray(total); val fill = start.copyOf(total)
        val root = IntArray(rootCount); var r = 0
        for (k in 0 until total) if (parent[k] >= 0) kid[fill[parent[k]]++] = k else root[r++] = k

        val spans: Series<Twin<Int>> = total j { k: Int -> open[k] j close[k] }
        @Suppress("UNCHECKED_CAST")
        val tags: Series<IOMemento> = total j { k: Int -> tag[k] as IOMemento }
        val depths: Series<Int> = total j { k: Int -> depth[k] }
        val childOf: (Int) -> Series<Int> = { k: Int -> val a = start[k]; (start[k + 1] - a) j { c: Int -> kid[a + c] } }
        val rowCache = arrayOfNulls<RowVec>(total)
        fun row(index: Int): RowVec {
            rowCache[index]?.let { return it }
            val children = childOf(index)
            val cursor: Cursor = children.size j { childIndex: Int -> row(children[childIndex]) }
            val row = (4 j { column: Int ->
                when (column) {
                    0 -> (open[index] as Any?) j COL_META[0]
                    1 -> (close[index] as Any?) j COL_META[1]
                    2 -> (tag[index] as Any?) j COL_META[2]
                    3 -> (cursor as Any?) j COL_META[3]
                    else -> error("4")
                }
            }) as RowVec
            rowCache[index] = row
            return row
        }
        return (rootCount j { rootIndex: Int -> row(root[rootIndex]) }) j FlatIndex(spans, tags, depths, childOf)
    }

    /** A growable primitive int list (32-bit lanes): offsets and ordinals never box. */
    fun series(): PackedIntBuf = PackedIntBuf(32)

    fun scanYaml0(src: Series<Byte>): Join<Cursor, FlatIndex> {
        var firstNonWhitespace = -1
        for (i in 0 until src.a) {
            val c = src.b(i).toInt().toChar()
            if (!c.isWhitespace()) {
                firstNonWhitespace = i
                break
            }
        }
        if (firstNonWhitespace != -1) {
            val firstChar = src.b(firstNonWhitespace).toInt().toChar()
            if (firstChar == '{' || firstChar == '[') {
                return scan0(src)
            }
        }

        val opens = series()
        val closes = series()
        val tags = ChunkedMutableSeries<IOMemento>()

        val srcStr = CharArray(src.a) { src.b(it).toInt().toChar() }.concatToString()
        val doc = borg.trikeshed.parse.yaml.YamlParser.parse(srcStr)

        val lines = srcStr.replace("\r\n", "\n").replace('\r', '\n').lines()
        val lineOffsets = IntArray(lines.size + 1)
        var offset = 0
        for (i in lines.indices) {
            lineOffsets[i] = offset
            offset += lines[i].length + 1
        }
        lineOffsets[lines.size] = srcStr.length

        fun getOffset(line: Int): Int {
            if (line <= 0) return 0
            if (line > lines.size) return srcStr.length
            return lineOffsets[line - 1]
        }

        fun getEndOffset(line: Int): Int {
            if (line <= 0) return 0
            if (line > lines.size) return srcStr.length
            return lineOffsets[line] - 1
        }

        fun add(open: Int, close: Int, tag: IOMemento) {
            opens.add(open)
            closes.add(close)
            tags.add(tag)
        }

        fun walk(node: borg.trikeshed.parse.yaml.YamlNode) {
            val open = getOffset(node.span.a)
            val close = minOf(srcStr.length - 1, getEndOffset(node.span.b))

            when (node) {
                is borg.trikeshed.parse.yaml.YamlMappingNode -> {
                    add(open, close, IOMemento.IoObject)
                    for (i in 0 until node.entries.a) {
                        val entry = node.entries.b(i)
                        val kOpen = getOffset(entry.span.a)
                        val lineText = lines[entry.span.a - 1]
                        val indent = lineText.length - lineText.trimStart().length
                        val kStart = kOpen + indent
                        val kClose = kStart + entry.key.a - 1
                        val kStartAdj = if (kStart > 0 && srcStr[kStart - 1] == '"') kStart - 1 else kStart
                        val kCloseAdj = if (kClose + 1 < srcStr.length && srcStr[kClose + 1] == '"') kClose + 1 else kClose

                        add(kStartAdj, kCloseAdj, IOMemento.IoString)
                        walk(entry.value)
                    }
                }
                is borg.trikeshed.parse.yaml.YamlSequenceNode -> {
                    add(open, close, IOMemento.IoArray)
                    for (i in 0 until node.items.a) {
                        walk(node.items.b(i))
                    }
                }
                is borg.trikeshed.parse.yaml.YamlScalarNode -> {
                    val valOpen = getOffset(node.span.a)
                    val valClose = minOf(srcStr.length - 1, getEndOffset(node.span.b))
                    val lineText = lines[node.span.a - 1]
                    val indent = lineText.length - lineText.trimStart().length
                    val vStart = valOpen + indent
                    
                    var vEnd = valClose
                    while (vEnd >= vStart && srcStr[vEnd].isWhitespace()) {
                        vEnd--
                    }

                    if (node.value == null) {
                        add(vStart, vEnd, IOMemento.IoNothing)
                    } else {
                        val vStr = CharArray(node.value!!.a) { node.value!!.b(it) }.concatToString()
                        val isNum = vStr.toDoubleOrNull() != null
                        if (isNum) {
                            add(vStart, vEnd, IOMemento.IoDouble)
                        } else if (vStr == "true" || vStr == "false") {
                            add(vStart, vEnd, IOMemento.IoBoolean)
                        } else {
                            val vStartAdj = if (vStart > 0 && srcStr[vStart - 1] == '"') vStart - 1 else vStart
                            val vEndAdj = if (vEnd + 1 < srcStr.length && srcStr[vEnd + 1] == '"') vEnd + 1 else vEnd
                            add(vStartAdj, vEndAdj, IOMemento.IoString)
                        }
                    }
                }
            }
        }

        walk(doc.root)
        return buildTree(opens, closes, tags)
    }

    fun scanIndex(src: Series<Byte>): ConfixIndex {
        val (tree, flat) = when (this) {
            CBOR -> scanCbor0(src)
            YAML -> scanYaml0(src)
            else -> scan0(src)
        }
        // Payload bounds must be checked even when neither derived facet is requested.
        for (index in 0 until flat.spans.size) {
            val span = flat.spans[index]
            require(span.b < span.a || (span.a >= 0 && span.b < src.size)) {
                "Token $index exceeds source bounds: ${span.a}..${span.b}"
            }
        }
        val keys by lazy { buildKeyIndex(flat, src) }

        val structuralNodes by lazy { buildStructuralNodes(flat, src) }

        return flat.spans.size j { operation: Any? ->
            when (operation) {
                ConfixIndexK.Spans -> flat.spans
                ConfixIndexK.Tags -> flat.tags
                ConfixIndexK.Depths -> flat.depths
                ConfixIndexK.DirectChildren -> flat.childOf
                ConfixIndexK.TreeCursor -> tree
                ConfixIndexK.KeyToChild -> ({ key: CharSequence -> keys[key.toString()] })
                ConfixIndexK.StructuralNodes -> structuralNodes
                else -> null
            }
        }
    }

    private fun buildKeyIndex(
        flat: FlatIndex,
        src: Series<Byte>
    ): LinkedHashMap<String, Int> {
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
                decodeTextSpan(src, open, close) // the same decode reify() gives the value, so lookups by key match
            }
            if (key !in keys) keys[key] = index
        }
        return keys
    }

    private fun buildStructuralNodes(
        flat: FlatIndex,
        src: Series<Byte>
    ): Series<String?> {
        val cids = arrayOfNulls<String>(flat.spans.size)
        for (i in flat.spans.size - 1 downTo 0) {
            val tag = flat.tags[i]
            val children = flat.childOf(i)
            if (tag == IOMemento.IoObject || tag == IOMemento.IoArray || children.size > 0) {
                // ⚡ Bolt: Optimize string concatenation in Confix hash calculation
                val hashStringBuilder = StringBuilder("node:\n")
                for (c in 0 until children.size) {
                    val childIdx = children[c]
                    hashStringBuilder.append(cids[childIdx]).append('\n')
                }
                cids[i] = ContentId.of(hashStringBuilder.toString().encodeToByteArray()).value
            } else {
                val span = flat.spans[i]
                val length = maxOf(0, span.b - span.a + 1)
                val bytes = ByteArray(length) { offset -> src[span.a + offset] }
                cids[i] = ContentId.of(bytes).value
            }
        }
        val structuralNodes = flat.spans.size j { i: Int -> cids[i] }
        return structuralNodes
    }
}
