package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.close
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.get
import borg.trikeshed.parse.confix.idx
import borg.trikeshed.parse.confix.kids
import borg.trikeshed.parse.confix.key
import borg.trikeshed.parse.confix.open
import borg.trikeshed.parse.confix.reify
import borg.trikeshed.parse.confix.rootCell
import borg.trikeshed.parse.confix.row
import borg.trikeshed.parse.confix.spanStr
import borg.trikeshed.parse.confix.src
import borg.trikeshed.parse.confix.tag

/** A compiled scalar expression over one row. */
typealias RowExpr = (RowVec) -> Any?

/**
 * The ordinal of [name]: an exact match, else the unique column whose name ends in `.name`
 * (a qualified scan column answering an unqualified reference), else the unique column
 * named by the last segment of a qualified [name].
 */
fun Series<CharSequence>.ordinalOf(name: CharSequence): Int {
    for (i in 0 until size) if (this[i].contentEquals(name)) return i
    var found = -1
    for (i in 0 until size) if (this[i].endsWithDot(name)) {
        require(found < 0) { "column reference \"$name\" is ambiguous" }
        found = i
    }
    if (found >= 0) return found
    var dot = name.length - 1
    while (dot >= 0 && name[dot] != '.') dot--
    if (dot >= 0 && dot + 1 < name.length) {
        val last = name.window(dot + 1, name.length)
        for (i in 0 until size) if (this[i].contentEquals(last)) {
            require(found < 0) { "column reference \"$name\" is ambiguous" }
            found = i
        }
    }
    require(found >= 0) { "column \"$name\" does not exist among ${(this α { it.toString() }).view.toList()}" }
    return found
}

/** [from, to) of this text as a window, no copy. */
private fun CharSequence.window(from: Int, to: Int): CharSequence = CharSeries(this).subSequence(from, to)

/** Whether this column name ends in `.name`, compared in place. */
private fun CharSequence.endsWithDot(name: CharSequence): Boolean {
    val at = length - name.length - 1
    if (at < 0 || this[at] != '.') return false
    for (i in name.indices) if (this[at + 1 + i] != name[i]) return false
    return true
}

/** Compile against the column [names] of the Cursor whose rows it will read. */
fun Expr.compile(names: Series<CharSequence>): RowExpr = when (this) {
    is ColumnRef -> names.ordinalOf(CharSeries(id.name)).let { ordinal -> { row -> SqlRuntime.cell(row, ordinal) } }
    is LitExpr -> literal(lit).let { value -> { _ -> value } }
    is BinaryExpr -> op.uppercase().let { op ->
        // A string literal pattern compiles once; any other right side (a column, NULL, a number) takes the row path, which null-propagates.
        val pattern = (right as? LitExpr)?.let { literal(it.lit) as? String }
        if ((op == "LIKE" || op == "ILIKE") && pattern != null) {
            val regex = likeRegex(pattern, ignoreCase = op == "ILIKE")
            left.compile(names).let { subject -> { row -> SqlRuntime.likeCompiled(subject(row), regex) } }
        } else if (op in REGEX_OPS && pattern != null) {
            val regex = regexOf(pattern, ignoreCase = op.endsWith("*"))
            val negated = op.startsWith("!")
            left.compile(names).let { subject -> { row -> SqlRuntime.regexCompiled(subject(row), regex, negated) } }
        } else binary(op, left.compile(names), right.compile(names))
    }
    is Not -> expr.compile(names).let { e -> { row -> SqlRuntime.not(e(row)) } }
    is Is -> expr.compile(names).let { e ->
        val test: RowExpr = value?.let { expected -> { row -> SqlRuntime.isBoolean(e(row), expected) } } ?: { row -> SqlRuntime.isNull(e(row)) }
        if (negated) { row -> SqlRuntime.not(test(row)) } else test
    }
    is InList -> expr.compile(names).let { e ->
        val parts = Array(items.size) { items[it].compile(names) }
        val test: RowExpr = { row -> SqlRuntime.inList(e(row), Array(parts.size) { parts[it](row) }) }
        if (negated) { row -> SqlRuntime.not(test(row)) } else test
    }
    is Quantified -> left.compile(names).let { l -> right.compile(names).let { r -> { row -> SqlRuntime.quantified(l(row), op, r(row), all) } } }
    // the planner turns every subselect into a hidden column before anything compiles
    is Subselect -> error("a subselect is a planned column, not an expression to compile: ${render()}")
    is Path -> cell.compile(names).let { source -> { row -> SqlRuntime.path(source(row), path, text) } }
    is Cast ->
        if (expr is LitExpr) literal(expr.lit).let { SqlRuntime.cast(it, type) }.let { folded -> { _ -> folded } }
        else expr.compile(names).let { source -> { row -> SqlRuntime.cast(source(row), type) } }
    is Call -> when {
        name in AGGREGATES -> error("function $name(...) is not available in a scalar context")
        name == SqlParser.CASE || name == SqlParser.CASE_WHEN -> {
            val searched = name == SqlParser.CASE_WHEN
            val head: RowExpr = if (searched) { _ -> null } else args[0].compile(names)
            val first = if (searched) 0 else 1
            val arms = Array(args.size - first) { args[it + first].compile(names) }
            ({ row -> SqlRuntime.case(head(row), searched, Array(arms.size) { i -> { arms[i](row) } }) })
        }
        else -> {
            SqlFunctions.require(name, args.size)
            val parts = Array(args.size) { args[it].compile(names) }
            ({ row -> SqlRuntime.call(name, Array(parts.size) { parts[it](row) }) })
        }
    }
    Star -> error("* is only an argument of COUNT")
}

/** pg's regular-expression operators. */
val REGEX_OPS = setOf("~", "~*", "!~", "!~*")

/** A regex operator's pattern, compiled; a pattern the engine refuses is pg's `2201B invalid_regular_expression`. */
fun regexOf(pattern: String, ignoreCase: Boolean): Regex =
    try { Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) }
    catch (e: Throwable) { error("invalid regular expression: ${e.message ?: pattern}") }

fun literal(lit: SqlNode): Any? = when (lit) {
    is StringLiteral -> lit.asString()
    is NumericLiteral -> lit.value
    is Identifier -> lit.asString()
    is BooleanLiteral -> lit.value
    is CellLiteral -> lit.value
    NullLiteral -> null
    else -> error("literal $lit")
}

fun binary(op: String, left: RowExpr, right: RowExpr): RowExpr = when (op) {
    "AND" -> { row -> left(row).let { l -> if (SqlRuntime.isFalse(l)) false else SqlRuntime.and(l, right(row)) } }
    "OR" -> { row -> left(row).let { l -> if (SqlRuntime.isTrue(l)) true else SqlRuntime.or(l, right(row)) } }
    "=" -> { row -> SqlRuntime.eq(left(row), right(row)) }
    "!=", "<>" -> { row -> SqlRuntime.ne(left(row), right(row)) }
    "<" -> { row -> SqlRuntime.lt(left(row), right(row)) }
    "<=" -> { row -> SqlRuntime.le(left(row), right(row)) }
    ">" -> { row -> SqlRuntime.gt(left(row), right(row)) }
    ">=" -> { row -> SqlRuntime.ge(left(row), right(row)) }
    "LIKE" -> { row -> SqlRuntime.like(left(row), right(row)) }
    "ILIKE" -> { row -> SqlRuntime.ilike(left(row), right(row)) }
    "@@" -> { row -> SqlRuntime.matches(left(row), right(row)) }
    "~" -> { row -> SqlRuntime.regexMatch(left(row), right(row), false, false) }
    "~*" -> { row -> SqlRuntime.regexMatch(left(row), right(row), true, false) }
    "!~" -> { row -> SqlRuntime.regexMatch(left(row), right(row), false, true) }
    "!~*" -> { row -> SqlRuntime.regexMatch(left(row), right(row), true, true) }
    "||" -> { row -> SqlRuntime.concat(left(row), right(row)) }
    "<=>" -> { row -> SqlRuntime.cosineDistance(left(row), right(row)) }
    "<->" -> { row -> SqlRuntime.l2Distance(left(row), right(row)) }
    "<#>" -> { row -> SqlRuntime.negInnerProduct(left(row), right(row)) }
    "+" -> { row -> SqlRuntime.add(left(row), right(row)) }
    "-" -> { row -> SqlRuntime.sub(left(row), right(row)) }
    "*" -> { row -> SqlRuntime.mul(left(row), right(row)) }
    "/" -> { row -> SqlRuntime.div(left(row), right(row)) }
    "%" -> { row -> SqlRuntime.mod(left(row), right(row)) }
    else -> error("operator $op")
}

/** `LIKE` as a regex: `%` any run, `_` any one character, and pg's default escape `\\` making the next character literal. */
fun likeRegex(pattern: CharSequence, ignoreCase: Boolean = false): Regex = Regex(buildString {
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\\' && i + 1 < pattern.length -> { appendLiteral(pattern[i + 1]); i++ }
            c == '%' -> append("[\\s\\S]*")
            c == '_' -> append("[\\s\\S]")
            else -> appendLiteral(c)
        }
        i++
    }
}, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

private const val REGEX_META = "\\^$.|?*+()[]{}"

/** [c] as a literal regex character: backslash-escaped when it is a metacharacter. */
private fun StringBuilder.appendLiteral(c: Char) { if (c in REGEX_META) append('\\'); append(c) }

fun integral(value: Any?): Boolean = value is Long || value is Int || value is Short || value is Byte

/** Total order over non-null cells: numbers numerically, then strings, booleans, others by text. */
fun compareCells(left: Any, right: Any): Int = when {
    integral(left) && integral(right) -> (left as Number).toLong().compareTo((right as Number).toLong())
    left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
    left is String && right is String -> left.compareTo(right)
    left is Boolean && right is Boolean -> left.compareTo(right)
    else -> compareText(scalarSpan(left), scalarSpan(right))
}

/** Lexicographic order of two texts by UTF-16 unit, read in place. */
private fun compareText(a: CharSequence, b: CharSequence): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) if (a[i] != b[i]) return a[i].compareTo(b[i])
    return a.length.compareTo(b.length)
}

/** The Confix cell inside [value], parsing JSON text when the cell holds a string. */
@Suppress("UNCHECKED_CAST")
fun confixCellOf(value: Any?): ConfixCell? = when (value) {
    null -> null
    is String -> runCatching { confixDoc(value).rootCell }.getOrNull()
    is Join<*, *> -> (value as? Join<Any?, Any?>)?.takeIf { it.a is Join<*, *> && it.b is Join<*, *> } as? ConfixCell
    else -> null
}

/** pg `->` / `->>`: a scalar member reifies; a container stays a cell, or its JSON text under [text]. */
fun navigate(value: Any?, path: Series<borg.trikeshed.parse.confix.JsPathElement>, text: Boolean): Any? {
    var cell = confixCellOf(value) ?: return null
    for (step in path.view) {
        val key = step.key
        val idx = step.idx
        cell = when {
            key != null -> cell[key]
            idx != null -> cell[idx]
            else -> null
        } ?: return null
    }
    return when (cell.row.tag) {
        IOMemento.IoObject, IOMemento.IoArray -> if (text) cell.src.spanStr(cell.row.open, cell.row.close) else cell
        IOMemento.IoNothing -> null
        else -> cell.reify().let { scalar -> if (text) scalar?.toString() else scalar }
    }
}

/** A cell as text: Confix containers by their JSON source, scalars by `toString`. */
fun scalarText(value: Any?): String = scalarSpan(value).toString()

/** [scalarText] unreified: a container answers a window over its source bytes, anything else its own text. */
private fun scalarSpan(value: Any?): CharSequence = confixCellOf(value)?.takeIf { value !is String }
    ?.let { ByteSpan(it.src, it.row.open, it.row.close) } ?: value.toString()

/** The bytes `open..close` of a Confix source read as characters, as [spanStr] reads them, without the copy. */
private class ByteSpan(val src: Series<Byte>, val open: Int, val close: Int) : CharSequence {
    override val length: Int get() = if (close < open) 0 else close - open + 1
    override fun get(index: Int): Char = src[open + index].toInt().toChar()
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = ByteSpan(src, open + startIndex, open + endIndex - 1)
    override fun toString(): String = src.spanStr(open, close)
}

/** [text] quoted as an SQL string literal, quotes doubled as it is read. */
private fun quoted(text: CharSequence): String = buildString(text.length + 2) {
    append('\'')
    for (i in 0 until text.length) { if (text[i] == '\'') append('\''); append(text[i]) }
    append('\'')
}

/** SQL text of an expression, used to match select-list expressions in ORDER BY and HAVING. */
fun Expr.render(): String = when (this) {
    is ColumnRef -> id.asString()
    is LitExpr -> when (val lit = lit) {
        is StringLiteral -> quoted(lit.value.cs)
        is NumericLiteral -> lit.text ?: renderNumber(lit.value)
        is BooleanLiteral -> if (lit.value) "TRUE" else "FALSE"
        NullLiteral -> "NULL"
        is Identifier -> lit.asString()
        is CellLiteral -> lit.value?.let { quoted(scalarSpan(it)) } ?: "NULL"
        else -> error("no SQL text for literal ${lit::class.simpleName}")
    }
    is BinaryExpr -> "(${left.render()} $op ${right.render()})"
    is Not -> "(NOT ${expr.render()})"
    is Is -> "(${expr.render()} IS ${if (negated) "NOT " else ""}${when (value) { null -> "NULL"; true -> "TRUE"; false -> "FALSE" }})"
    is InList -> "(${expr.render()} ${if (negated) "NOT " else ""}IN (${(items α { it.render() }).view.joinToString(", ")}))"
    is Subselect -> (if (array) "ARRAY" else "") + "(" + select.render() + ")"
    is Quantified -> "(${left.render()} $op ${if (all) "ALL" else "ANY"} (${right.render()}))"
    is Call -> "$name(${(args α { it.render() }).view.joinToString(", ")})"
    is Path -> cell.render() + path.view.mapIndexed { i, step ->
        (if (text && i == path.size - 1) "->>" else "->") + (step.key?.let { "'$it'" } ?: step.idx.toString())
    }.joinToString("")
    is Cast -> "${expr.render()}::$type"
    Star -> "*"
}

/**
 * A number's canonical text for a literal that was not parsed (the planner's and the surfaces' own):
 * integral values print as integers; a fractional value prints as plain decimal with at least one
 * fraction digit, so it agrees with [numberText] of the same value's lexeme on every target.
 */
fun renderNumber(n: Number): String = when (n) {
    is Double, is Float -> plainDecimal(n.toDouble())
    else -> n.toString()
}

/**
 * A double as plain decimal text with at least one fraction digit: the shortest round-trip digits every
 * target prints, with the exponent notation each target chooses differently (`1.0E-4` on the JVM,
 * `0.0001` on JS) folded back into the digits.
 */
fun plainDecimal(d: Double): String {
    if (!d.isFinite()) return d.toString()
    if (d > -1e15 && d < 1e15 && d.toLong().toDouble() == d) return d.toLong().toString() + ".0"
    val s = d.toString()
    val neg = s[0] == '-'
    val start = if (neg) 1 else 0
    var e = s.length
    for (i in start until s.length) if (s[i] == 'E' || s[i] == 'e') { e = i; break }
    var exponent = 0
    var j = e + 1
    val minus = j < s.length && s[j] == '-'
    if (j < s.length && (s[j] == '+' || minus)) j++
    while (j < s.length) exponent = exponent * 10 + (s[j++] - '0')
    if (minus) exponent = -exponent
    var dot = -1
    val digits = StringBuilder(e - start)
    for (i in start until e) if (s[i] == '.') dot = i - start else digits.append(s[i])
    val intLen = (if (dot < 0) e - start else dot) + exponent
    val intEnd = maxOf(intLen, 0)
    var k = 0
    while (k < intEnd && (if (k < digits.length) digits[k] else '0') == '0') k++
    // fraction digit f sits at digits[intLen + f]; before the digits it is a zero
    fun frac(f: Int): Char = if (f + intLen < 0) '0' else digits[f + intLen]
    var fracEnd = maxOf(digits.length - intLen, 0)
    while (fracEnd > 0 && frac(fracEnd - 1) == '0') fracEnd--
    return buildString {
        if (neg) append('-')
        if (k == intEnd) append('0') else while (k < intEnd) { append(if (k < digits.length) digits[k] else '0'); k++ }
        append('.')
        if (fracEnd == 0) append('0') else for (f in 0 until fracEnd) append(frac(f))
    }
}

/**
 * The statement's canonical structural text, the identity a [borg.trikeshed.cursor.query.QueryReceipt]
 * hashes: clause by clause over [Expr.render]. Not dialect SQL (identifiers are not quoted); the M2
 * printer is.
 */
fun SelectStmt.render(): String = buildString {
    append("SELECT ")
    append(columns.view.joinToString(", ") { c -> c.expr.render() + (c.alias?.let { " AS " + it.asString() } ?: "") })
    from?.let { t -> append(" FROM ").append(t.render()) }
    for (j in joins.view) {
        append(if (j.on == null) " CROSS JOIN " else if (j.outer) " LEFT JOIN " else " JOIN ").append(j.table.render())
        j.on?.let { append(" ON ").append(it.render()) }
    }
    where?.let { append(" WHERE ").append(it.render()) }
    if (groupBy.size > 0) append(" GROUP BY ").append(groupBy.view.joinToString(", ") { it.render() })
    having?.let { append(" HAVING ").append(it.render()) }
    for ((all, arm) in union.view) append(if (all) " UNION ALL " else " UNION ").append(arm.render())
    if (orderBy.size > 0) append(" ORDER BY ").append(orderBy.view.joinToString(", ") { o ->
        o.expr.render() + (if (o.descending) " DESC" else " ASC") + (o.nullsFirst?.let { if (it) " NULLS FIRST" else " NULLS LAST" } ?: "")
    })
    limit?.let { append(" LIMIT ").append(it) }
    offset?.let { append(" OFFSET ").append(it) }
}

/** A FROM item's text: `name`, or `name(args)` for a table function, then its alias. */
fun TableRef.render(): String = buildString {
    append(name.asString())
    args?.let { a -> append('(').append((a α { it.render() }).view.joinToString(", ")).append(')') }
    alias?.let { append(' ').append(it.asString()) }
}

/** A vector cell as floats: a FloatArray / DoubleArray, a Confix array of numbers, or pgvector text `[1, 2.5, -3]`. */
fun vectorOf(value: Any?): FloatArray? = when (value) {
    null -> null
    is FloatArray -> value
    is DoubleArray -> FloatArray(value.size) { value[it].toFloat() }
    is String -> {
        val body = trimmed(value)
        require(body.length >= 2 && body[0] == '[' && body[body.length - 1] == ']') { "invalid input syntax for type vector: \"$value\"" }
        val parts = body[1 j body.length - 1] / ','
        if (parts.size == 1 && trimmed(parts[0].cs).length == 0) FloatArray(0)
        else FloatArray(parts.size) { i -> trimmed(parts[i].cs).asString().toFloatOrNull() ?: error("invalid input syntax for type vector: \"$value\"") }
    }
    else -> {
        val cell = confixCellOf(value)?.takeIf { it.row.tag == IOMemento.IoArray }
            ?: error("cannot read a vector from ${value::class.simpleName}")
        val kids = cell.row.kids
        FloatArray(kids.size) { i -> (kids[i].reify(cell.src) as? Number)?.toFloat() ?: error("vector element $i is not a number") }
    }
}

/** An RDF term's spelling: a Turtle literal, an IRI in angle brackets or a blank node. A cast of one that is not a number is NULL, as SPARQL's type error is, not pg's syntax error. */
fun isRdfTerm(text: CharSequence): Boolean =
    text.length > 0 && (text[0] == '"' || text[0] == '<' || (text.length > 1 && text[0] == '_' && text[1] == ':'))

/** Where a Turtle literal's lexical form ends — the closing quote of `"3.5"^^<dt>` or `"x"@en` — or -1 when [text] is not one. */
private fun lexicalEnd(text: CharSequence): Int {
    if (text.length == 0 || text[0] != '"') return -1
    var i = 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            '"' -> return i
            else -> i++
        }
    }
    return -1
}

/** A Turtle literal's lexical form as a window, else [text] itself. */
fun lexicalOf(text: CharSequence): CharSequence = lexicalEnd(text).let { end -> if (end < 0) text else text.window(1, end) }

/** The same for a String edge (the RDF query parsers want a String back): one substring, no window. */
fun lexicalOf(text: String): String = lexicalEnd(text).let { end -> if (end < 0) text else text.substring(1, end) }

/** [text] without surrounding whitespace, as a window. */
private fun trimmed(text: CharSequence): CharSeries = CharSeries(text).trim.slice

/** `value::type`. Integral types answer Long and fractional types Double, as the arithmetic does. */
fun castTo(value: Any?, type: String): Any? {
    if (value == null) return null
    return when (type) {
        "vector" -> vectorOf(value)
        "int", "integer", "int2", "int4", "int8", "smallint", "bigint", "oid" ->
            if (value is Number) value.toLong() else trimmed(scalarSpan(value)).let { t -> trimmed(lexicalOf(t)).asString().toLongOrNull() ?: if (isRdfTerm(t)) null else error("invalid input syntax for type $type: \"$value\"") }
        "float", "float4", "float8", "real", "double", "numeric", "decimal" ->
            if (value is Number) value.toDouble() else trimmed(scalarSpan(value)).let { t -> trimmed(lexicalOf(t)).asString().toDoubleOrNull() ?: if (isRdfTerm(t)) null else error("invalid input syntax for type $type: \"$value\"") }
        "text", "varchar", "string", "char", "name", "regclass", "regtype", "regnamespace", "regproc" -> scalarText(value)
        "bool", "boolean" -> when (value) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            else -> trimmed(scalarSpan(value)).let { t ->
                when {
                    t.isOneOf("t", "true", "y", "yes", "on", "1") -> true
                    t.isOneOf("f", "false", "n", "no", "off", "0") -> false
                    else -> error("invalid input syntax for type boolean: \"$value\"")
                }
            }
        }
        else -> error("type \"$type\" does not exist")
    }
}

private fun CharSequence.isOneOf(vararg words: String): Boolean = words.any { it.contentEquals(this, ignoreCase = true) }
