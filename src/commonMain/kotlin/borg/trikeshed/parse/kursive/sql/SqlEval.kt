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
    val suffix = ".$name"
    var found = -1
    for (i in 0 until size) if (this[i].endsWith(suffix)) {
        require(found < 0) { "column reference \"$name\" is ambiguous" }
        found = i
    }
    if (found >= 0) return found
    val last = name.toString().substringAfterLast('.', "")
    if (last.isNotEmpty() && last.length < name.length) for (i in 0 until size) if (this[i].contentEquals(last)) {
        require(found < 0) { "column reference \"$name\" is ambiguous" }
        found = i
    }
    require(found >= 0) { "column \"$name\" does not exist among ${(this α { it.toString() }).view.toList()}" }
    return found
}

/** Compile against the column [names] of the Cursor whose rows it will read. */
fun Expr.compile(names: Series<CharSequence>): RowExpr = when (this) {
    is ColumnRef -> names.ordinalOf(id.asString()).let { ordinal -> { row -> SqlRuntime.cell(row, ordinal) } }
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

/** SQL `LIKE`: `%` any run, `_` any one character, everything else literal; `ILIKE` under [ignoreCase]. */
/** `LIKE` as a regex: `%` any run, `_` any one character, and pg's default escape `\\` making the next character literal. */
fun likeRegex(pattern: String, ignoreCase: Boolean = false): Regex = Regex(buildString {
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\\' && i + 1 < pattern.length -> { append(Regex.escape(pattern[i + 1].toString())); i++ }
            c == '%' -> append("[\\s\\S]*")
            c == '_' -> append("[\\s\\S]")
            else -> append(Regex.escape(c.toString()))
        }
        i++
    }
}, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

fun integral(value: Any?): Boolean = value is Long || value is Int || value is Short || value is Byte

/** Total order over non-null cells: numbers numerically, then strings, booleans, others by text. */
fun compareCells(left: Any, right: Any): Int = when {
    integral(left) && integral(right) -> (left as Number).toLong().compareTo((right as Number).toLong())
    left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
    left is String && right is String -> left.compareTo(right)
    left is Boolean && right is Boolean -> left.compareTo(right)
    else -> scalarText(left).compareTo(scalarText(right))
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
fun scalarText(value: Any?): String = confixCellOf(value)?.takeIf { value !is String }
    ?.let { it.src.spanStr(it.row.open, it.row.close) } ?: value.toString()

/** SQL text of an expression, used to match select-list expressions in ORDER BY and HAVING. */
fun Expr.render(): String = when (this) {
    is ColumnRef -> id.asString()
    is LitExpr -> when (val lit = lit) {
        is StringLiteral -> "'" + lit.asString().replace("'", "''") + "'"
        is NumericLiteral -> lit.text ?: renderNumber(lit.value)
        is BooleanLiteral -> if (lit.value) "TRUE" else "FALSE"
        NullLiteral -> "NULL"
        is Identifier -> lit.asString()
        else -> error("no SQL text for literal ${lit::class.simpleName}")
    }
    is BinaryExpr -> "(${left.render()} $op ${right.render()})"
    is Not -> "(NOT ${expr.render()})"
    is Is -> "(${expr.render()} IS ${if (negated) "NOT " else ""}${when (value) { null -> "NULL"; true -> "TRUE"; false -> "FALSE" }})"
    is InList -> "(${expr.render()} ${if (negated) "NOT " else ""}IN (${(items α { it.render() }).view.joinToString(", ")}))"
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
    val neg = s.startsWith("-")
    val body = if (neg) s.substring(1) else s
    val e = body.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (e < 0) body else body.substring(0, e)
    val exponent = if (e < 0) 0 else body.substring(e + 1).removePrefix("+").toInt()
    val dot = mantissa.indexOf('.')
    val digits = mantissa.replace(".", "")
    val intLen = (if (dot < 0) mantissa.length else dot) + exponent
    val out = when {
        intLen <= 0 -> "0." + "0".repeat(-intLen) + digits
        intLen >= digits.length -> digits + "0".repeat(intLen - digits.length) + ".0"
        else -> digits.substring(0, intLen) + "." + digits.substring(intLen)
    }
    val intPart = out.substringBefore('.').trimStart('0').ifEmpty { "0" }
    val fracPart = out.substringAfter('.').trimEnd('0').ifEmpty { "0" }
    return (if (neg) "-" else "") + intPart + "." + fracPart
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
        append(if (j.outer) " LEFT JOIN " else " JOIN ").append(j.table.render())
        j.on?.let { append(" ON ").append(it.render()) }
    }
    where?.let { append(" WHERE ").append(it.render()) }
    if (groupBy.size > 0) append(" GROUP BY ").append(groupBy.view.joinToString(", ") { it.render() })
    having?.let { append(" HAVING ").append(it.render()) }
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
        val body = value.trim()
        require(body.startsWith("[") && body.endsWith("]")) { "invalid input syntax for type vector: \"$value\"" }
        val parts = body.substring(1, body.length - 1).split(',').map { it.trim() }
        if (parts.size == 1 && parts[0].isEmpty()) FloatArray(0)
        else FloatArray(parts.size) { i -> parts[i].toFloatOrNull() ?: error("invalid input syntax for type vector: \"$value\"") }
    }
    else -> {
        val cell = confixCellOf(value)?.takeIf { it.row.tag == IOMemento.IoArray }
            ?: error("cannot read a vector from ${value::class.simpleName}")
        val kids = cell.row.kids
        FloatArray(kids.size) { i -> (kids[i].reify(cell.src) as? Number)?.toFloat() ?: error("vector element $i is not a number") }
    }
}

/** An RDF term's spelling: a Turtle literal, an IRI in angle brackets or a blank node. A cast of one that is not a number is NULL, as SPARQL's type error is, not pg's syntax error. */
fun isRdfTerm(text: String): Boolean = text.startsWith("\"") || text.startsWith("<") || text.startsWith("_:")

/** A Turtle literal's lexical form — `"3.5"^^<dt>` and `"x"@en` read as `3.5` and `x` — else [text] itself. */
fun lexicalOf(text: String): String {
    if (!text.startsWith("\"")) return text
    var i = 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            '"' -> return text.substring(1, i)
            else -> i++
        }
    }
    return text
}

/** `value::type`. Integral types answer Long and fractional types Double, as the arithmetic does. */
fun castTo(value: Any?, type: String): Any? {
    if (value == null) return null
    return when (type) {
        "vector" -> vectorOf(value)
        "int", "integer", "int2", "int4", "int8", "smallint", "bigint", "oid" ->
            if (value is Number) value.toLong() else scalarText(value).trim().let { t -> lexicalOf(t).trim().toLongOrNull() ?: if (isRdfTerm(t)) null else error("invalid input syntax for type $type: \"$value\"") }
        "float", "float4", "float8", "real", "double", "numeric", "decimal" ->
            if (value is Number) value.toDouble() else scalarText(value).trim().let { t -> lexicalOf(t).trim().toDoubleOrNull() ?: if (isRdfTerm(t)) null else error("invalid input syntax for type $type: \"$value\"") }
        "text", "varchar", "string", "char", "name", "regclass", "regtype", "regnamespace", "regproc" -> scalarText(value)
        "bool", "boolean" -> when (value) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            else -> when (scalarText(value).trim().lowercase()) {
                "t", "true", "y", "yes", "on", "1" -> true
                "f", "false", "n", "no", "off", "0" -> false
                else -> error("invalid input syntax for type boolean: \"$value\"")
            }
        }
        else -> error("type \"$type\" does not exist")
    }
}
