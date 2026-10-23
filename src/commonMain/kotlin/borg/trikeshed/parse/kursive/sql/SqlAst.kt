package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.JsPath
import borg.trikeshed.parse.confix.JsPathElement
import borg.trikeshed.parse.confix.idxOf
import borg.trikeshed.parse.confix.keyOf

private val CharSeries.cseq: CharSequence get() = cs

// Compact SQL AST using Series<Char> (zero-copy views)
// Canonical root — kursive, miniduck, and couch all consume from here.
sealed interface SqlNode

class Identifier(val name: Series<Char>) : SqlNode {
    fun asString(): String = name.asString()
    val s: String get() = asString()
}

class StringLiteral(val value: Series<Char>) : SqlNode {
    fun asString(): String = value.asString()
    val s: String get() = asString()
}

class NumericLiteral(val value: Number) : SqlNode

/** `TRUE` / `FALSE`. */
data class BooleanLiteral(val value: Boolean) : SqlNode

/** `NULL`. */
data object NullLiteral : SqlNode

sealed interface Expr : SqlNode

class ColumnRef(val id: Identifier) : Expr
class LitExpr(val lit: SqlNode) : Expr
data class BinaryExpr(val left: Expr, val op: String, val right: Expr) : Expr
/** `name(args)`; `COUNT(*)` carries [Star] as its argument. */
data class Call(val name: String, val args: Series<Expr>) : Expr
/** The `*` argument of `COUNT(*)`. */
data object Star : Expr
/** `expr::type` (pg, DuckDB cast). */
data class Cast(val expr: Expr, val type: String) : Expr
/**
 * Semi-structured access into a Confix cell: pg `->` / `->>` / `#>>`, DuckDB `->`,
 * Snowflake `col:a.b[0]`. [text] is the `->>` / `#>>` form, which answers the scalar as text.
 */
data class Path(val cell: Expr, val path: JsPath, val text: Boolean) : Expr
/** `NOT expr`, three-valued: NOT NULL is NULL. */
data class Not(val expr: Expr) : Expr
/** `expr IS [NOT] NULL | TRUE | FALSE` ([value] null = NULL / UNKNOWN); never NULL itself. */
data class Is(val expr: Expr, val value: Boolean?, val negated: Boolean = false) : Expr
/** `expr [NOT] IN (items)`: true on a match, else NULL when an item is NULL, else false. */
data class InList(val expr: Expr, val items: Series<Expr>, val negated: Boolean = false) : Expr
data class Column(val expr: Expr, val alias: Identifier? = null) : SqlNode
data class TableRef(val name: Identifier, val alias: Identifier? = null) : SqlNode
/** `[LEFT [OUTER] | INNER] JOIN table ON expr`. */
data class JoinClause(val table: TableRef, val on: Expr?, val outer: Boolean) : SqlNode
/** [nullsFirst] null is pg's default: NULLS LAST ascending, NULLS FIRST descending. */
data class OrderItem(val expr: Expr, val descending: Boolean, val nullsFirst: Boolean? = null) : SqlNode
data class SelectStmt(
    val columns: Series<Column>,
    val from: TableRef?,
    val where: Expr?,
    val joins: Series<JoinClause> = emptySeriesOf(),
    val groupBy: Series<Expr> = emptySeriesOf(),
    val having: Expr? = null,
    val orderBy: Series<OrderItem> = emptySeriesOf(),
    val limit: Int? = null,
    val offset: Int? = null,
) : SqlNode

/**
 * Recursive-descent parser for a SELECT subset (SELECT ... FROM ... WHERE ...)
 * Works on CharSeries and avoids creating Strings until callers ask for them.
 */
class SqlParser(private val cs: CharSeries) {
    companion object {
        val RESERVED = setOf(
            "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "JOIN", "ON", "AS", "UNION", "DISTINCT",
            "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "ASC", "DESC", "AND", "OR",
            "NOT", "IN", "IS", "NULL", "TRUE", "FALSE", "LIKE", "ILIKE",
        )

        fun parse(src: Series<Char>): SelectStmt? = SqlParser(CharSeries(src).trim).parseSelect()
        fun parse(text: String): SelectStmt? {
            // try the recursive-descent parser first
            val parsed = parse(text.toSeries())
            if (parsed != null) return parsed

            // fallback: simple regex-based parser
            val selUp: CharSequence = text.uppercase()
            val selIndex = selUp.indexOf("SELECT")
            val fromIndex = selUp.indexOf("FROM")
            if (selIndex < 0 || fromIndex < 0 || fromIndex <= selIndex) return null
            val colsPart = text.substring(selIndex + "SELECT".length, fromIndex).trim()
            val afterFrom = text.substring(fromIndex + "FROM".length).trim()
            val parts = afterFrom.split(Regex("\\s+WHERE\\s+", RegexOption.IGNORE_CASE), 2)
            val tablePart = parts[0].trim().split(Regex("\\s+"))[0]
            val wherePart = if (parts.size > 1) parts[1].trim() else null

            val cols: List<Column> =
                if (colsPart == "*") listOf(Column(LitExpr(StringLiteral("*".toSeries())), null)) else colsPart.split(
                    ',',
                ).map { col -> Column(ColumnRef(Identifier(col.trim().toSeries())), null) }

            val from = TableRef(Identifier(tablePart.toSeries()))

            var whereExpr: Expr? = null
            if (!wherePart.isNullOrEmpty()) {
                val comp =
                    Regex("(?i)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(=|!=|<>|>=|<=|>|<)\\s*('([^']*)'|\\d+(?:\\.\\d+)?)\\s*")
                val cm = comp.matchEntire(wherePart)
                if (cm != null) {
                    val colName = cm.groupValues[1]
                    val op = cm.groupValues[2].uppercase().toString()
                    val litRaw = cm.groupValues[3]
                    val litNode: SqlNode = if (litRaw.startsWith("'") && litRaw.endsWith("'")) {
                        StringLiteral(litRaw.substring(1, litRaw.length - 1).toSeries())
                    } else {
                        val numStr = litRaw
                        if (numStr.contains('.')) NumericLiteral(numStr.toDouble()) else NumericLiteral(numStr.toLong())
                    }
                    whereExpr = BinaryExpr(ColumnRef(Identifier(colName.toSeries())), op, LitExpr(litNode))
                }
            }

            return SelectStmt(cols.toSeries(), from, whereExpr)
        }
    }

    fun skipWs() {
        while (cs.pos < cs.limit && cs[cs.pos].isWhitespace()) cs.pos++
    }

    fun peek(): Char = if (cs.pos < cs.limit) cs[cs.pos] else '\u0000'

    fun matchKeyword(keyword: String): Boolean {
        val save = cs.pos
        val k = keyword.uppercase()

        for (i in k.indices) {
            if (cs.pos + i >= cs.limit) {
                cs.pos = save; return false
            }
            if (cs[cs.pos + i].uppercaseChar() != k[i]) {
                cs.pos = save; return false
            }
        }
        val next = cs.pos + k.length
        if (next < cs.limit) {
            val nc = cs[next]
            if (nc.isLetterOrDigit() || nc == '_' || nc == '$' || nc == '.') {
                cs.pos = save; return false
            }
        }
        cs.pos += k.length
        return true
    }

    fun parseIdentifier(): Identifier? {
        skipWs()
        if (peek() == '"') {
            val tmp = CharSeries(cs, cs.pos, cs.limit)
            tmp.pos++
            val start = tmp.pos
            if (!tmp.seekTo('"', '\\')) return null
            val end = tmp.pos - 1
            val name = cs[start until end]
            cs.pos = tmp.pos
            return Identifier(name)
        }
        val start = cs.pos
        if (cs.pos < cs.limit && (cs[cs.pos].isLetter() || cs[cs.pos] == '_')) {
            cs.pos++
            while (cs.pos < cs.limit) {
                val c = cs[cs.pos]
                if (c.isLetterOrDigit() || c == '_' || c == '.' || c == '$') cs.pos++ else break
            }
            val name = cs[start until cs.pos]
            return Identifier(name)
        }
        return null
    }

    fun parseStringLiteral(): StringLiteral? {
        skipWs()
        if (peek() != '\'') return null
        val tmp = CharSeries(cs, cs.pos, cs.limit)
        tmp.pos++
        val start = tmp.pos
        if (!tmp.seekTo('\'', '\\')) return null
        val end = tmp.pos - 1
        val value = cs[start until end]
        cs.pos = tmp.pos
        return StringLiteral(value)
    }

    fun parseNumberLit(): NumericLiteral? {
        skipWs()
        val start = cs.pos
        var seen = false
        if (cs.pos < cs.limit && (cs[cs.pos] == '+' || cs[cs.pos] == '-')) cs.pos++
        while (cs.pos < cs.limit && cs[cs.pos].isDigit()) {
            cs.pos++; seen = true
        }
        val fraction = cs.pos < cs.limit && cs[cs.pos] == '.'
        if (fraction) {
            cs.pos++
            while (cs.pos < cs.limit && cs[cs.pos].isDigit()) {
                cs.pos++; seen = true
            }
        }
        if (!seen) {
            cs.pos = start; return null
        }
        val slice: Series<Char> = cs[start until cs.pos]

        val num = if (fraction) slice.parseDoubleOrNull() ?: return null.also { cs.pos = start } else slice.parseLong()
        val value = when (num) {
            is Long -> if (num in Int.MIN_VALUE..Int.MAX_VALUE) num.toInt() else num
            is Double -> num
            else -> num
        }
        return NumericLiteral(value as Number)
    }

    fun parseTerm(): Expr? {
        skipWs()
        if (peek() == '(') {
            cs.pos++
            val e = parseExpr()
            skipWs()
            if (peek() == ')') cs.pos++
            return parsePostfix(e ?: return null)
        }
        parseStringLiteral()?.let { return parsePostfix(LitExpr(it)) }
        parseNumberLit()?.let { return parsePostfix(LitExpr(it)) }
        if (matchKeyword("NULL")) return parsePostfix(LitExpr(NullLiteral))
        if (matchKeyword("TRUE")) return parsePostfix(LitExpr(BooleanLiteral(true)))
        if (matchKeyword("FALSE")) return parsePostfix(LitExpr(BooleanLiteral(false)))
        val id = parseIdentifier() ?: return null
        skipWs()
        if (peek() == '(') {
            cs.pos++
            skipWs()
            val args = mutableListOf<Expr>()
            if (peek() == '*') { cs.pos++; args += Star }
            else while (peek() != ')') {
                args += parseExpr() ?: return null
                skipWs()
                if (peek() == ',') { cs.pos++; skipWs() } else break
            }
            skipWs()
            if (peek() != ')') return null
            cs.pos++
            return parsePostfix(Call(id.asString().lowercase(), args.toSeries()))
        }
        if (peek() == ':' && !(cs.pos + 1 < cs.limit && cs[cs.pos + 1] == ':')) {
            cs.pos++
            return parsePostfix(Path(ColumnRef(id), parseColonPath(), text = false))
        }
        return parsePostfix(ColumnRef(id))
    }

    /** `->` paths and `::type` casts, in any order: `doc->>'n'::int`, `'[1,2]'::vector`. */
    fun parsePostfix(head: Expr): Expr {
        var left = parsePath(head)
        while (true) {
            val save = cs.pos
            skipWs()
            if (!matchSymbol("::")) { cs.pos = save; return left }
            skipWs()
            val type = parseIdentifier() ?: run { cs.pos = save; return left }
            left = parsePath(Cast(left, type.asString().lowercase()))
        }
    }

    /** Snowflake `col:a.b[0]` segments after the colon. */
    fun parseColonPath(): JsPath {
        val steps = mutableListOf<JsPathElement>()
        while (cs.pos < cs.limit) {
            if (peek() == '[') {
                cs.pos++
                val start = cs.pos
                while (cs.pos < cs.limit && cs[cs.pos].isDigit()) cs.pos++
                steps += idxOf(cs[start until cs.pos].asString().toInt())
                if (peek() == ']') cs.pos++
            } else {
                if (peek() == '.') cs.pos++
                val start = cs.pos
                while (cs.pos < cs.limit && (cs[cs.pos].isLetterOrDigit() || cs[cs.pos] == '_')) cs.pos++
                if (start == cs.pos) break
                steps += keyOf(cs[start until cs.pos].asString())
            }
        }
        return steps.toSeries()
    }

    /** pg / DuckDB postfix `->`, `->>`, `#>>`. */
    fun parsePath(head: Expr): Expr {
        var left = head
        while (true) {
            val save = cs.pos
            skipWs()
            val text: Boolean
            val hash = peek() == '#'
            when {
                matchSymbol("->>") || matchSymbol("#>>") -> text = true
                matchSymbol("->") || matchSymbol("#>") -> text = false
                else -> { cs.pos = save; return left }
            }
            skipWs()
            val step: JsPath = if (hash) {
                val lit = parseStringLiteral() ?: run { cs.pos = save; return left }
                val body = lit.asString().trim().removePrefix("{").removeSuffix("}")
                body.split(',').map { seg -> seg.trim().toIntOrNull()?.let { idxOf(it) } ?: keyOf(seg.trim()) }.toSeries()
            } else {
                parseStringLiteral()?.let { s_[keyOf(it.asString())] }
                    ?: parseNumberLit()?.let { s_[idxOf(it.value.toInt())] }
                    ?: run { cs.pos = save; return left }
            }
            left = Path(left, step, text)
        }
    }

    fun matchSymbol(symbol: String): Boolean {
        if (cs.pos + symbol.length > cs.limit) return false
        for (i in symbol.indices) if (cs[cs.pos + i] != symbol[i]) return false
        cs.pos += symbol.length
        return true
    }

    fun parseMultiplicative(): Expr? {
        var left = parseTerm() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            val op = peek()
            if (op != '*' && op != '/' && op != '%') { cs.pos = save; return left }
            cs.pos++
            val right = parseTerm() ?: run { cs.pos = save; return left }
            left = BinaryExpr(left, op.toString(), right)
        }
    }

    fun parseAdditive(): Expr? {
        var left = parseMultiplicative() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            val op = peek()
            val arrow = cs.pos + 1 < cs.limit && cs[cs.pos + 1] == '>'
            val concat = op == '|' && cs.pos + 1 < cs.limit && cs[cs.pos + 1] == '|'
            if (!((op == '+' || op == '-') && !arrow) && !concat) { cs.pos = save; return left }
            cs.pos += if (concat) 2 else 1
            val right = parseMultiplicative() ?: run { cs.pos = save; return left }
            left = BinaryExpr(left, if (concat) "||" else op.toString(), right)
        }
    }

    /** pgvector `<=>` cosine distance, `<->` L2 distance, `<#>` negative inner product: looser than `+ -`, tighter than comparisons. */
    fun parseVector(): Expr? {
        var left = parseAdditive() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            val op = listOf("<=>", "<->", "<#>").firstOrNull { matchSymbol(it) } ?: run { cs.pos = save; return left }
            val right = parseAdditive() ?: run { cs.pos = save; return left }
            left = BinaryExpr(left, op, right)
        }
    }

    /** `[NOT] IN (list)`, `[NOT] LIKE` and `[NOT] ILIKE`: tighter than comparisons, as pg. */
    fun parsePattern(): Expr? {
        val left = parseVector() ?: return null
        val save = cs.pos
        skipWs()
        val negated = matchKeyword("NOT").also { skipWs() }
        if (matchKeyword("IN")) {
            skipWs()
            if (peek() != '(') { cs.pos = save; return left }
            cs.pos++
            val items = mutableListOf<Expr>()
            while (true) {
                items += parseExpr() ?: run { cs.pos = save; return left }
                skipWs()
                if (peek() == ',') cs.pos++ else break
            }
            if (peek() != ')') { cs.pos = save; return left }
            cs.pos++
            return InList(left, items.toSeries(), negated)
        }
        for (op in listOf("ILIKE", "LIKE")) if (matchKeyword(op)) {
            skipWs()
            val right = parseVector() ?: run { cs.pos = save; return left }
            return BinaryExpr(left, op, right).let { if (negated) Not(it) else it }
        }
        cs.pos = save
        return left
    }

    fun parseComparison(): Expr? {
        val left = parsePattern() ?: return null
        val save = cs.pos
        skipWs()
        for (op in listOf("!=", "<>", ">=", "<=", "=", ">", "<")) {
            if (!matchSymbol(op)) continue
            skipWs()
            val right = parsePattern() ?: run { cs.pos = save; return left }
            return BinaryExpr(left, op, right)
        }
        cs.pos = save
        return left
    }

    /** `IS [NOT] NULL | UNKNOWN | TRUE | FALSE`: looser than comparisons, tighter than `NOT`, as pg. */
    fun parseIs(): Expr? {
        var left = parseComparison() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            if (!matchKeyword("IS")) { cs.pos = save; return left }
            skipWs()
            val negated = matchKeyword("NOT").also { skipWs() }
            val value = when {
                matchKeyword("NULL") || matchKeyword("UNKNOWN") -> null
                matchKeyword("TRUE") -> true
                matchKeyword("FALSE") -> false
                else -> { cs.pos = save; return left }
            }
            left = Is(left, value, negated)
        }
    }

    /** `NOT` binds looser than `IS` and tighter than `AND`. */
    fun parseNot(): Expr? {
        val save = cs.pos
        skipWs()
        if (!matchKeyword("NOT")) return parseIs()
        return Not(parseNot() ?: run { cs.pos = save; return null })
    }

    fun parseAnd(): Expr? {
        var left = parseNot() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            if (matchKeyword("AND")) {
                skipWs()
                val right = parseNot() ?: run { cs.pos = save; break }
                left = BinaryExpr(left, "AND", right)
            } else {
                cs.pos = save; break
            }
        }
        return left
    }

    fun parseExpr(): Expr? {
        var left = parseAnd() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            if (matchKeyword("OR")) {
                skipWs()
                val right = parseAnd() ?: run { cs.pos = save; break }
                left = BinaryExpr(left, "OR", right)
            } else {
                cs.pos = save; break
            }
        }
        return left
    }

    fun parseColumn(): Column? {
        skipWs()
        val expr = parseExpr() ?: return null
        skipWs()
        var alias: Identifier? = null
        val save2 = cs.pos
        if (matchKeyword("AS")) {
            skipWs()
            alias = parseIdentifier()
            if (alias == null) {
                cs.pos = save2
            }
        } else {
            val maybe = parseIdentifier()
            if (maybe != null) {
                val nameStr = maybe.name.asString().uppercase().toString()
                val reserved =
                    RESERVED
                if (nameStr in reserved) {
                    cs.pos = save2
                } else {
                    alias = maybe
                }
            } else cs.pos = save2
        }
        return Column(expr as Expr, alias)
    }

    fun parseSelectList(): Series<Column> {
        skipWs()
        val cols = mutableListOf<Column>()
        if (peek() == '*') {
            cs.pos++; cols += Column(LitExpr(StringLiteral("*".toSeries())), null); return cols.toSeries()
        }
        while (true) {
            val c = parseColumn() ?: break
            cols += c
            skipWs()
            if (peek() == ',') {
                cs.pos++; continue
            } else break
        }
        return cols.toSeries()
    }

    fun parseTableRef(): TableRef? {
        skipWs()
        val name = parseIdentifier() ?: return null
        skipWs()
        var alias: Identifier? = null
        val save = cs.pos
        if (matchKeyword("AS")) {
            skipWs()
            alias = parseIdentifier()
            if (alias == null) cs.pos = save
        } else {
            val maybe = parseIdentifier()
            if (maybe != null) {
                val nameStr = maybe.name.asString().uppercase().toString()
                val reserved = RESERVED
                if (nameStr in reserved) {
                    cs.pos = save
                } else {
                    alias = maybe
                }
            } else cs.pos = save
        }
        return TableRef(name, alias)
    }

    fun parseSelect(): SelectStmt? {
        skipWs()
        if (!matchKeyword("SELECT")) return null
        skipWs()
        val cols = parseSelectList()
        skipWs()
        var from: TableRef? = null
        if (matchKeyword("FROM")) {
            skipWs()
            from = parseTableRef()
        }
        val joins = mutableListOf<JoinClause>()
        while (true) {
            val save = cs.pos
            skipWs()
            val outer = matchKeyword("LEFT")
            if (outer) { skipWs(); matchKeyword("OUTER"); skipWs() } else if (matchKeyword("INNER")) skipWs()
            if (!matchKeyword("JOIN")) { cs.pos = save; break }
            val table = parseTableRef() ?: return null
            skipWs()
            val on = if (matchKeyword("ON")) { skipWs(); parseExpr() } else null
            joins += JoinClause(table, on, outer)
        }
        skipWs()
        var where: Expr? = null
        if (matchKeyword("WHERE")) {
            skipWs()
            where = parseExpr()
        }
        skipWs()
        val groupBy = mutableListOf<Expr>()
        if (matchKeyword("GROUP")) {
            skipWs()
            if (!matchKeyword("BY")) return null
            while (true) {
                skipWs()
                groupBy += parseExpr() ?: return null
                skipWs()
                if (peek() != ',') break
                cs.pos++
            }
        }
        skipWs()
        val having = if (matchKeyword("HAVING")) { skipWs(); parseExpr() } else null
        skipWs()
        val orderBy = mutableListOf<OrderItem>()
        if (matchKeyword("ORDER")) {
            skipWs()
            if (!matchKeyword("BY")) return null
            while (true) {
                skipWs()
                val e = parseExpr() ?: return null
                skipWs()
                val descending = matchKeyword("DESC")
                if (!descending) matchKeyword("ASC")
                skipWs()
                val save = cs.pos
                val nullsFirst = if (!matchKeyword("NULLS")) null else {
                    skipWs()
                    if (matchKeyword("FIRST")) true else if (matchKeyword("LAST")) false else { cs.pos = save; null }
                }
                orderBy += OrderItem(e, descending, nullsFirst)
                skipWs()
                if (peek() != ',') break
                cs.pos++
            }
        }
        skipWs()
        val limit = if (matchKeyword("LIMIT")) { skipWs(); parseNumberLit()?.value?.toInt() } else null
        skipWs()
        val offset = if (matchKeyword("OFFSET")) { skipWs(); parseNumberLit()?.value?.toInt() } else null
        skipWs()
        if (peek() == ';') cs.pos++
        skipWs()
        if (cs.pos < cs.limit) return null
        return SelectStmt(cols, from, where, joins.toSeries(), groupBy.toSeries(), having, orderBy.toSeries(), limit, offset)
    }
}
