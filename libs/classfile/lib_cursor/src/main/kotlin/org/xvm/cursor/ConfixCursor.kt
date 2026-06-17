package org.xvm.cursor
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.lib.α
import borg.trikeshed.lib.toList

/**
 * ConfixCursor — Confix facade for JSON/YAML/CBOR/CSV with lazy row/column values
 * and facet composition.
 *
 * Design principles:
 *   - Lazy evaluation throughout — no bulk loading of data
 *   - Facet composition = column projection + join
 *   - Rows parsed on iteration, column values parsed on first access then cached
 *   - Zero external dependencies for JSON/CSV; YAML/CBOR stubbed for TrikeShed integration
 *
 * Format discriminator: [Syntax] from TrikeShed covers JSON/YAML/CBOR;
 * `null` signals CSV (not represented in [Syntax]).
 */

// ── Column schema ────────────────────────────────────────────────────────────

enum class ConfixType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    NULL
}

data class ConfixColumn(val name: String, val type: ConfixType)

// ── Row interface: lazy column values ────────────────────────────────────────

/**
 * A single row in a [ConfixCursor]. Column values are parsed on first access
 * and cached for subsequent reads.
 */
interface ConfixRow {
    /** Number of columns in this row */
    val size: Int

    /** Column value by zero-based index — parsed on first access, then cached */
    operator fun get(index: Int): Any?

    /** Column value by name — resolves index then delegates to [get] */
    operator fun get(name: String): Any?

    /** Typed access helpers by index */
    fun string(index: Int): String? = get(index)?.toString()
    fun int(index: Int): Int? = (get(index) as? Number)?.toInt()
    fun long(index: Int): Long? = (get(index) as? Number)?.toLong()
    fun double(index: Int): Double? = (get(index) as? Number)?.toDouble()
    fun boolean(index: Int): Boolean? = get(index) as? Boolean

    /** Typed access helpers by name */
    fun string(name: String): String? = string(columnNames().indexOf(name))
    fun int(name: String): Int? = int(columnNames().indexOf(name))
    fun long(name: String): Long? = long(columnNames().indexOf(name))
    fun double(name: String): Double? = double(columnNames().indexOf(name))
    fun boolean(name: String): Boolean? = boolean(columnNames().indexOf(name))

    /** The column names for this row (matches the parent cursor schema) */
    fun columnNames(): List<String>

    /** Materialize all values as a list (forces parsing of every column) */
    fun toList(): List<Any?> = ((0 until size) α { get(it) }).view.toList()

    /** Materialize all values as a name→value map */
    fun toMap(): Map<String, Any?> = columnNames().withIndex().associate { it.value to get(it.index) }
}

// ── Lazy row implementation ──────────────────────────────────────────────────

/**
 * A [ConfixRow] backed by a raw value extractor that is called lazily per-column.
 *
 * @param columnNames the column schema names
 * @param rawExtractor given a column index, returns the raw (unparsed) value — typically a String
 * @param coercer given a column index and the raw value, returns the typed value
 */
internal class LazyConfixRow(
    private val _columnNames: List<String>,
    private val rawExtractor: (Int) -> Any?,
    private val coercer: (Int, Any?) -> Any?
) : ConfixRow {

    override val size: Int get() = _columnNames.size

    /** Lazy cache: null = not yet computed, the array slot holds the result */
    private val cache = arrayOfNulls<Any?>(size)
    private val resolved = BooleanArray(size)

    override fun get(index: Int): Any? {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("Column index $index out of range [0, $size)")
        if (!resolved[index]) {
            cache[index] = coercer(index, rawExtractor(index))
            resolved[index] = true
        }
        return cache[index]
    }

    override fun get(name: String): Any? {
        val idx = _columnNames.indexOf(name)
        if (idx < 0) throw NoSuchElementException("No column named '$name'")
        return get(idx)
    }

    override fun columnNames(): List<String> = _columnNames
}

// ── Main cursor ──────────────────────────────────────────────────────────────

/**
 * Lazy cursor over a Confix data source (JSON/YAML/CBOR/CSV).
 *
 * Rows are parsed on iteration — never bulk-loaded. Column values within each
 * row are parsed on first access and cached.
 *
 * Facet composition:
 *   - [facet] projects a subset of columns
 *   - [join] merges rows with another cursor on a shared column
 */
class ConfixCursor private constructor(
    private val source: String,
    /** [Syntax] from TrikeShed for JSON/YAML/CBOR; `null` for CSV */
    private val format: Syntax?,
    private val columnOverride: List<ConfixColumn>?,
    private val rowSequenceFactory: ((List<ConfixColumn>) -> Sequence<ConfixRow>)?
) {

    constructor(source: String, format: Syntax?) : this(source, format, null, null)

    /** Cached schema — parsed once on first access */
    private var _columns: List<ConfixColumn>? = null

    /** Schema for this cursor */
    fun columns(): List<ConfixColumn> {
        if (_columns == null) {
            _columns = columnOverride ?: detectSchema(source, format)
        }
        return _columns!!
    }

    /**
     * Lazy sequence of rows. Each row is parsed when the iterator produces it,
     * not before. Column values within each row are further lazily parsed.
     */
    fun rows(): Sequence<ConfixRow> {
        val schema = columns()
        val factory = rowSequenceFactory ?: { cols -> parseRows(source, format, cols) }
        return factory(schema)
    }

    // ── Facet composition: projection ──────────────────────────────────────

    /**
     * Project only the named columns. Returns a new [ConfixCursor] that
     * lazily wraps this cursor's row sequence with a column filter.
     */
    fun facet(vararg columnNames: String): ConfixCursor {
        val nameSet = columnNames.toSet()
        val parentSchema = columns()
        val projectedSchema = parentSchema.filter { it.name in nameSet }
        val projectedIndices = parentSchema.mapIndexedNotNull { idx, col ->
            if (col.name in nameSet) idx else null
        }
        val projectedNames = (projectedSchema α { it.name }).view.toList()

        return ConfixCursor(source, format, projectedSchema) { _ ->
            this.rows() α { parentRow ->
                LazyConfixRow(
                    _columnNames = projectedNames,
                    rawExtractor = { i -> parentRow[projectedIndices[i]] },
                    coercer = { _, v -> v }
                )
            }
        }
    }

    // ── Facet composition: join ────────────────────────────────────────────

    /**
     * Faceted join on a matching column. Returns a new [ConfixCursor] whose
     * rows combine columns from both cursors where [on] matches.
     *
     * The left side is streamed lazily; the right side is collected into a
     * hash map indexed by the join key (materialized once).
     */
    fun join(other: ConfixCursor, on: String): ConfixCursor {
        val leftCols = this.columns()
        val rightCols = other.columns()
        val rightWithoutKey = rightCols.filter { it.name != on }
        val joinedSchema = leftCols + rightWithoutKey
        val joinedNames = (joinedSchema α { it.name }).view.toList()

        return ConfixCursor(source, format, joinedSchema) { _ ->
            // materialize right side indexed by join key
            val rightIndex: Map<Any?, List<ConfixRow>> = other.rows()
                .groupBy { it[on] }

            this.rows().flatMap { leftRow ->
                val key = leftRow[on]
                val matchingRights = rightIndex[key] ?: emptyList()
                matchingRights α { rightRow ->
                    LazyConfixRow(
                        _columnNames = joinedNames,
                        rawExtractor = { i ->
                            if (i < leftCols.size) leftRow[i]
                            else rightRow[rightCols.indexOfFirst { it.name == joinedNames[i] }]
                        },
                        coercer = { _, v -> v }
                    )
                }.asSequence()
            }
        }
    }

    // ── Schema detection ───────────────────────────────────────────────────

    private fun detectSchema(src: String, fmt: Syntax?): List<ConfixColumn> = when (fmt) {
        null -> CsvParser.detectSchema(src)
        Syntax.JSON -> JsonParser.detectSchema(src)
        Syntax.YAML -> TODO("YAML schema detection — will use TrikeShed parsing")
        Syntax.CBOR -> TODO("CBOR schema detection — will use TrikeShed parsing")
    }

    // ── Row parsing dispatch ───────────────────────────────────────────────

    private fun parseRows(src: String, fmt: Syntax?, cols: List<ConfixColumn>): Sequence<ConfixRow> =
        when (fmt) {
            null -> CsvParser.parseRows(src, cols)
            Syntax.JSON -> JsonParser.parseRows(src, cols)
            Syntax.YAML -> TODO("YAML row parsing — will use TrikeShed parsing")
            Syntax.CBOR -> TODO("CBOR row parsing — will use TrikeShed parsing")
        }

    // ── Inline JSON parser (no external deps) ──────────────────────────────

    /**
     * Minimal JSON parser for arrays of objects: `[ { ... }, { ... } ]`.
     * Handles nested strings with escapes, numbers, booleans, null.
     * Does NOT handle nested objects/arrays as values (treats them as raw strings).
     */
    private object JsonParser {

        fun detectSchema(src: String): List<ConfixColumn> {
            val trimmed = src.trim()
            if (!trimmed.startsWith('[')) return emptyList()

            // find the first object { ... } and extract its keys+types
            val obj = extractFirstObject(trimmed) ?: return emptyList()
            return (obj.entries α { (k, v) ->
                ConfixColumn(k, inferType(v))
            }).view.toList()
            }
        }

        fun parseRows(src: String, cols: List<ConfixColumn>): Sequence<ConfixRow> {
            val trimmed = src.trim()
            val colNames = (cols α { it.name }).view.toList()
            val colTypes = (cols α { it.type }).view.toList()
            return object : Sequence<ConfixRow> {
                override fun iterator(): Iterator<ConfixRow> = ObjectIterator(trimmed, colNames, colTypes)
            }
        }

        private class ObjectIterator(
            src: String,
            private val colNames: List<String>,
            private val colTypes: List<ConfixType>
        ) : Iterator<ConfixRow> {
            private val data: String = src.trim()
            private var pos: Int = if (data.startsWith('[')) 1 else 0

            override fun hasNext(): Boolean {
                skipWhitespace()
                return pos < data.length && data[pos] != ']'
            }

            override fun next(): ConfixRow {
                skipWhitespace()
                if (pos >= data.length || data[pos] == ']') throw NoSuchElementException()
                val fields = mutableMapOf<String, Any?>()
                parseObject(fields)
                val rawValues = (colNames α { fields[it] }).view.toList().toTypedArray()
                return LazyConfixRow(
                    _columnNames = colNames,
                    rawExtractor = { i -> rawValues[i] },
                    coercer = { i, v -> coerce(v, colTypes[i]) }
                )
            }

            private fun skipWhitespace() {
                while (pos < data.length && data[pos] in " \t\r\n") pos++
            }

            private fun parseObject(fields: MutableMap<String, Any?>) {
                skipWhitespace()
                expect('{')
                skipWhitespace()
                if (pos < data.length && data[pos] == '}') { pos++; return }
                while (true) {
                    skipWhitespace()
                    val key = parseString()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    val value = parseValue()
                    fields[key] = value
                    skipWhitespace()
                    if (pos < data.length && data[pos] == ',') { pos++; continue }
                    break
                }
                skipWhitespace()
                expect('}')
            }

            private fun parseValue(): Any? {
                skipWhitespace()
                if (pos >= data.length) return null
                return when (data[pos]) {
                    '"' -> parseString()
                    '{' -> parseRawNested()
                    '[' -> parseRawNested()
                    't', 'f' -> parseBoolean()
                    'n' -> parseNull()
                    else -> parseNumber()
                }
            }

            private fun parseString(): String {
                expect('"')
                val sb = StringBuilder()
                while (pos < data.length) {
                    val ch = data[pos++]
                    if (ch == '"') return sb.toString()
                    if (ch == '\\') {
                        if (pos >= data.length) break
                        when (data[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > data.length) break
                                val hex = data.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                return sb.toString()
            }

            private fun parseNumber(): Number {
                val start = pos
                if (pos < data.length && data[pos] == '-') pos++
                while (pos < data.length && data[pos].isDigit()) pos++
                val isFloat = pos < data.length && (data[pos] == '.' || data[pos] == 'e' || data[pos] == 'E')
                if (isFloat) {
                    if (pos < data.length && data[pos] == '.') {
                        pos++
                        while (pos < data.length && data[pos].isDigit()) pos++
                    }
                    if (pos < data.length && (data[pos] == 'e' || data[pos] == 'E')) {
                        pos++
                        if (pos < data.length && (data[pos] == '+' || data[pos] == '-')) pos++
                        while (pos < data.length && data[pos].isDigit()) pos++
                    }
                    return data.substring(start, pos).toDouble()
                }
                val longVal = data.substring(start, pos).toLong()
                return if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) longVal.toInt() else longVal
            }

            private fun parseBoolean(): Boolean {
                return if (data.startsWith("true", pos)) {
                    pos += 4; true
                } else {
                    pos += 5; false // "false"
                }
            }

            private fun parseNull(): Any? {
                pos += 4 // "null"
                return null
            }

            /** Parse a nested object or array into a raw string value */
            private fun parseRawNested(): String {
                val start = pos
                val open = data[pos]
                val close = if (open == '{') '}' else ']'
                var depth = 1
                pos++
                while (pos < data.length && depth > 0) {
                    if (data[pos] == '"') {
                        pos++
                        while (pos < data.length && data[pos] != '"') {
                            if (data[pos] == '\\') pos++
                            pos++
                        }
                        pos++ // closing quote
                    } else {
                        if (data[pos] == open) depth++
                        else if (data[pos] == close) depth--
                        pos++
                    }
                }
                return data.substring(start, pos)
            }

            private fun expect(ch: Char) {
                if (pos < data.length && data[pos] == ch) {
                    pos++
                } else {
                    throw IllegalStateException("Expected '$ch' at position $pos but got '${if (pos < data.length) data[pos] else "EOF"}'")
                }
            }
        }

        private fun extractFirstObject(src: String): Map<String, Any?>? {
            val trimmed = src.trim()
            if (!trimmed.startsWith('[')) return null
            val iter = ObjectIterator(trimmed, emptyList(), emptyList())
            // manually find the first '{'
            var p = 1
            while (p < trimmed.length && trimmed[p] in " \t\r\n") p++
            if (p >= trimmed.length || trimmed[p] != '{') return null

            // use a simpler approach: parse the first object manually
            val fields = mutableMapOf<String, Any?>()
            var idx = p
            idx++ // skip '{'
            while (idx < trimmed.length) {
                while (idx < trimmed.length && trimmed[idx] in " \t\r\n") idx++
                if (trimmed[idx] == '}') break
                // parse key
                if (trimmed[idx] != '"') break
                idx++
                val keyStart = idx
                while (idx < trimmed.length && trimmed[idx] != '"') {
                    if (trimmed[idx] == '\\') idx++
                    idx++
                }
                val key = trimmed.substring(keyStart, idx)
                idx++ // closing quote
                while (idx < trimmed.length && trimmed[idx] in " \t\r\n") idx++
                if (idx >= trimmed.length || trimmed[idx] != ':') break
                idx++
                while (idx < trimmed.length && trimmed[idx] in " \t\r\n") idx++
                // parse value — simplified
                val valueStart = idx
                when {
                    trimmed[idx] == '"' -> {
                        idx++
                        while (idx < trimmed.length && trimmed[idx] != '"') {
                            if (trimmed[idx] == '\\') idx++
                            idx++
                        }
                        idx++
                        fields[key] = trimmed.substring(valueStart + 1, idx - 1)
                    }
                    trimmed[idx] == 't' -> { idx += 4; fields[key] = true }
                    trimmed[idx] == 'f' -> { idx += 5; fields[key] = false }
                    trimmed[idx] == 'n' -> { idx += 4; fields[key] = null }
                    else -> {
                        // number
                        while (idx < trimmed.length && trimmed[idx] !in ",} \t\r\n") idx++
                        val numStr = trimmed.substring(valueStart, idx)
                        fields[key] = numStr.toDoubleOrNull() ?: numStr.toLongOrNull() ?: numStr
                    }
                }
                while (idx < trimmed.length && trimmed[idx] in " \t\r\n") idx++
                if (idx < trimmed.length && trimmed[idx] == ',') idx++
            }
            return fields
        }

        private fun inferType(value: Any?): ConfixType = when (value) {
            null -> ConfixType.NULL
            is Boolean -> ConfixType.BOOLEAN
            is Int -> ConfixType.INT
            is Long -> ConfixType.LONG
            is Double -> ConfixType.DOUBLE
            is String -> ConfixType.STRING
            else -> ConfixType.STRING
        }
    }

    // ── Inline CSV parser (RFC 4180) ───────────────────────────────────────

    /**
     * RFC 4180 compliant CSV parser.
     * - First row is the header (column names)
     * - Fields may be quoted with double-quotes
     * - Double-quotes inside quoted fields are escaped as ""
     * - CRLF or LF line endings
     */
    private object CsvParser {

        fun detectSchema(src: String): List<ConfixColumn> {
            val firstLine = firstLine(src) ?: return emptyList()
            val headers = parseLine(firstLine)
            // peek at second line to infer types
            val secondLineStart = firstLineEnd(src)
            val secondLine = if (secondLineStart < src.length) {
                val end = lineEnd(src, secondLineStart)
                if (end > secondLineStart) parseLine(src.substring(secondLineStart, end)) else emptyList()
            } else emptyList()

            return headers.mapIndexed { i, name ->
                val type = if (i < secondLine.size) inferCsvType(secondLine[i]) else ConfixType.STRING
                ConfixColumn(name, type)
            }
        }

        fun parseRows(src: String, cols: List<ConfixColumn>): Sequence<ConfixRow> {
            val colNames = (cols α { it.name }).view.toList()
            val colTypes = (cols α { it.type }).view.toList()
            // skip header line
            val headerEnd = firstLineEnd(src)
            if (headerEnd >= src.length) return emptySequence()

            return object : Sequence<ConfixRow> {
                override fun iterator(): Iterator<ConfixRow> = CsvRowIterator(src, headerEnd, colNames, colTypes)
            }
        }

        private class CsvRowIterator(
            private val src: String,
            private var offset: Int,
            private val colNames: List<String>,
            private val colTypes: List<ConfixType>
        ) : Iterator<ConfixRow> {
            override fun hasNext(): Boolean = offset < src.length

            override fun next(): ConfixRow {
                if (offset >= src.length) throw NoSuchElementException()
                val end = lineEnd(src, offset)
                val line = src.substring(offset, end)
                // advance past the line ending
                offset = if (end < src.length && src[end] == '\r' && end + 1 < src.length && src[end + 1] == '\n') end + 2
                else if (end < src.length && src[end] == '\n') end + 1
                else end

                val rawFields = parseLine(line).toMutableList()
                // pad or trim to match schema
                while (rawFields.size < colNames.size) rawFields.add("")
                if (rawFields.size > colNames.size) rawFields.subList(colNames.size, rawFields.size).clear()

                val captured = rawFields.toTypedArray()
                return LazyConfixRow(
                    _columnNames = colNames,
                    rawExtractor = { i -> captured[i] },
                    coercer = { i, v -> coerceCsvValue(v, colTypes[i]) }
                )
            }
        }

        /** Parse a single CSV line into fields, respecting RFC 4180 quoting */
        internal fun parseLine(line: String): List<String> {
            val fields = mutableListOf<String>()
            var i = 0
            val n = line.length
            while (i <= n) {
                if (i == n) { fields.add(""); break }
                if (line[i] == '"') {
                    // quoted field
                    val sb = StringBuilder()
                    i++ // opening quote
                    while (i < n) {
                        if (line[i] == '"') {
                            if (i + 1 < n && line[i + 1] == '"') {
                                sb.append('"')
                                i += 2
                            } else {
                                i++ // closing quote
                                break
                            }
                        } else {
                            sb.append(line[i])
                            i++
                        }
                    }
                    fields.add(sb.toString())
                    if (i < n && line[i] == ',') i++ // skip comma
                } else {
                    // unquoted field
                    val commaPos = line.indexOf(',', i)
                    if (commaPos < 0) {
                        fields.add(line.substring(i))
                        break
                    } else {
                        fields.add(line.substring(i, commaPos))
                        i = commaPos + 1
                    }
                }
            }
            return fields
        }

        private fun firstLine(src: String): String? {
            val end = lineEnd(src, 0)
            return if (end > 0) src.substring(0, end) else if (src.isNotEmpty()) src else null
        }

        private fun firstLineEnd(src: String): Int {
            val end = lineEnd(src, 0)
            // skip past the newline
            return if (end < src.length && src[end] == '\r' && end + 1 < src.length && src[end + 1] == '\n') end + 2
            else if (end < src.length && src[end] == '\n') end + 1
            else src.length
        }

        private fun lineEnd(src: String, start: Int): Int {
            for (i in start until src.length) {
                if (src[i] == '\r' || src[i] == '\n') return i
            }
            return src.length
        }

        private fun inferCsvType(value: String): ConfixType {
            if (value.isEmpty()) return ConfixType.STRING
            if (value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true)) return ConfixType.BOOLEAN
            if (value == "null") return ConfixType.NULL
            value.toIntOrNull()?.let { return ConfixType.INT }
            value.toLongOrNull()?.let { return ConfixType.LONG }
            value.toDoubleOrNull()?.let { return ConfixType.DOUBLE }
            return ConfixType.STRING
        }
    }

    companion object {
        /** Coerce a raw value to the expected [ConfixType] */
        internal fun coerce(value: Any?, type: ConfixType): Any? = when (type) {
            ConfixType.STRING -> value?.toString()
            ConfixType.INT -> when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: value
                null -> null
                else -> value
            }
            ConfixType.LONG -> when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: value
                null -> null
                else -> value
            }
            ConfixType.DOUBLE -> when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: value
                null -> null
                else -> value
            }
            ConfixType.BOOLEAN -> when (value) {
                is Boolean -> value
                is String -> value.toBooleanStrictOrNull() ?: value
                null -> null
                else -> value
            }
            ConfixType.NULL -> null
        }

        /** Coerce a CSV string value to the expected type */
        internal fun coerceCsvValue(value: Any?, type: ConfixType): Any? {
            if (value !is String) return value
            return when (type) {
                ConfixType.STRING -> value
                ConfixType.INT -> value.toIntOrNull() ?: value
                ConfixType.LONG -> value.toLongOrNull() ?: value
                ConfixType.DOUBLE -> value.toDoubleOrNull() ?: value
                ConfixType.BOOLEAN -> value.toBooleanStrictOrNull() ?: value
                ConfixType.NULL -> null
            }
        }
    }
}
