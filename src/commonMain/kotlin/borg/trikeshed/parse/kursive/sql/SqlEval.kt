package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.close
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.get
import borg.trikeshed.parse.confix.idx
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
    is BinaryExpr ->
        if (op.uppercase() == "LIKE" && right is LitExpr) {
            val regex = likeRegex(literal(right.lit).toString())
            left.compile(names).let { subject -> { row -> SqlRuntime.likeCompiled(subject(row), regex) } }
        } else binary(op.uppercase(), left.compile(names), right.compile(names))
    is Path -> cell.compile(names).let { source -> { row -> SqlRuntime.path(source(row), path, text) } }
    is Call -> error("function $name(...) is not available in a scalar context")
    Star -> error("* is only an argument of COUNT")
}

fun literal(lit: SqlNode): Any? = when (lit) {
    is StringLiteral -> lit.asString()
    is NumericLiteral -> lit.value
    is Identifier -> lit.asString()
    else -> error("literal $lit")
}

fun binary(op: String, left: RowExpr, right: RowExpr): RowExpr = when (op) {
    "AND" -> { row -> left(row) == true && right(row) == true }
    "OR" -> { row -> left(row) == true || right(row) == true }
    "=" -> { row -> SqlRuntime.eq(left(row), right(row)) }
    "!=", "<>" -> { row -> SqlRuntime.ne(left(row), right(row)) }
    "<" -> { row -> SqlRuntime.lt(left(row), right(row)) }
    "<=" -> { row -> SqlRuntime.le(left(row), right(row)) }
    ">" -> { row -> SqlRuntime.gt(left(row), right(row)) }
    ">=" -> { row -> SqlRuntime.ge(left(row), right(row)) }
    "LIKE" -> { row -> SqlRuntime.like(left(row), right(row)) }
    "||" -> { row -> SqlRuntime.concat(left(row), right(row)) }
    "+" -> { row -> SqlRuntime.add(left(row), right(row)) }
    "-" -> { row -> SqlRuntime.sub(left(row), right(row)) }
    "*" -> { row -> SqlRuntime.mul(left(row), right(row)) }
    "/" -> { row -> SqlRuntime.div(left(row), right(row)) }
    "%" -> { row -> SqlRuntime.mod(left(row), right(row)) }
    else -> error("operator $op")
}

/** SQL `LIKE`: `%` any run, `_` any one character, everything else literal. */
fun likeRegex(pattern: String): Regex = Regex(buildString {
    for (c in pattern) when (c) {
        '%' -> append("[\\s\\S]*")
        '_' -> append("[\\s\\S]")
        else -> append(Regex.escape(c.toString()))
    }
})

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
        else -> lit.toString()
    }
    is BinaryExpr -> "(${left.render()} $op ${right.render()})"
    is Call -> "$name(${(args α { it.render() }).view.joinToString(", ")})"
    is Path -> cell.render() + path.view.mapIndexed { i, step ->
        (if (text && i == path.size - 1) "->>" else "->") + (step.key?.let { "'$it'" } ?: step.idx.toString())
    }.joinToString("")
    Star -> "*"
}
