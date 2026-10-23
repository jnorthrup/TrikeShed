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
        if ((op == "LIKE" || op == "ILIKE") && right is LitExpr) {
            val regex = likeRegex(literal(right.lit).toString(), ignoreCase = op == "ILIKE")
            left.compile(names).let { subject -> { row -> SqlRuntime.likeCompiled(subject(row), regex) } }
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
    is Call -> error("function $name(...) is not available in a scalar context")
    Star -> error("* is only an argument of COUNT")
}

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
fun likeRegex(pattern: String, ignoreCase: Boolean = false): Regex = Regex(buildString {
    for (c in pattern) when (c) {
        '%' -> append("[\\s\\S]*")
        '_' -> append("[\\s\\S]")
        else -> append(Regex.escape(c.toString()))
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
        is NumericLiteral -> lit.value.toString()
        is BooleanLiteral -> if (lit.value) "TRUE" else "FALSE"
        NullLiteral -> "NULL"
        else -> lit.toString()
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

/** `value::type`. Integral types answer Long and fractional types Double, as the arithmetic does. */
fun castTo(value: Any?, type: String): Any? {
    if (value == null) return null
    return when (type) {
        "vector" -> vectorOf(value)
        "int", "integer", "int2", "int4", "int8", "smallint", "bigint" ->
            if (value is Number) value.toLong() else scalarText(value).trim().toLongOrNull() ?: error("invalid input syntax for type $type: \"$value\"")
        "float", "float4", "float8", "real", "double", "numeric", "decimal" ->
            if (value is Number) value.toDouble() else scalarText(value).trim().toDoubleOrNull() ?: error("invalid input syntax for type $type: \"$value\"")
        "text", "varchar", "string", "char", "regclass" -> scalarText(value)
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
