package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.collections.SeriesBuffer
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.JsPath
import borg.trikeshed.parse.confix.JsPathElement
import borg.trikeshed.parse.confix.idxOf
import borg.trikeshed.parse.confix.keyOf

/** Case-folded equality of a window with [word]; no String is made. */
private fun Series<Char>.eqFold(word: String): Boolean {
    if (size != word.length) return false
    for (i in 0 until size) if (this[i].uppercaseChar() != word[i].uppercaseChar()) return false
    return true
}

/** A [SqlParser.RESERVED] word, whatever its case: an alias probe makes no String. */
private fun Series<Char>.isReserved(): Boolean {
    for (w in SqlParser.RESERVED) if (eqFold(w)) return true
    return false
}

/** The lower-cased name of a call or type, [SqlParser.PG_CATALOG] dropped as a window before the one String is made. */
private fun Series<Char>.catalogName(): String {
    val p = SqlParser.PG_CATALOG
    var qualified = size >= p.length
    if (qualified) for (i in p.indices) if (this[i].lowercaseChar() != p[i]) { qualified = false; break }
    return (if (qualified) drop(p.length) else this).asString().lowercase()
}

/** The interned spelling of a one-character arithmetic operator. */
private fun opText(c: Char): String = when (c) { '*' -> "*"; '/' -> "/"; '%' -> "%"; '+' -> "+"; else -> "-" }

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

/** A number; [text] is its canonical lexeme (see [numberText]) when it was parsed, the text a statement's receipt hashes. */
class NumericLiteral(val value: Number, val text: String? = null) : SqlNode

/**
 * The canonical text of a decimal lexeme `[+-]digits[.digits]`: no `+`, no leading integer zeros,
 * no trailing fraction zeros (one digit kept, so `2.0` stays fractional); null for any other spelling.
 */
fun numberText(lexeme: CharSequence): String? = numberText(lexeme.length j lexeme::get)

/** [numberText] over a window: the digits are trimmed by moving bounds, and the one String made is the result. */
fun numberText(lexeme: Series<Char>): String? {
    val n = lexeme.size
    val neg = n > 0 && lexeme[0] == '-'
    val from = if (neg || (n > 0 && lexeme[0] == '+')) 1 else 0
    var dot = -1
    for (i in from until n) if (lexeme[i] == '.') { dot = i; break }
    val intEnd = if (dot < 0) n else dot
    if (intEnd == from) return null
    for (i in from until intEnd) if (lexeme[i] !in '0'..'9') return null
    var fracEnd = n
    if (dot >= 0) {
        if (dot + 1 == n) return null
        for (i in dot + 1 until n) if (lexeme[i] !in '0'..'9') return null
        while (fracEnd > dot + 2 && lexeme[fracEnd - 1] == '0') fracEnd--
    }
    var intStart = from
    while (intStart < intEnd - 1 && lexeme[intStart] == '0') intStart++
    val zero = intEnd - intStart == 1 && lexeme[intStart] == '0' && (dot < 0 || (fracEnd - dot == 2 && lexeme[dot + 1] == '0'))
    return buildString {
        if (neg && !zero) append('-')
        for (i in intStart until intEnd) append(lexeme[i])
        if (dot >= 0) { append('.'); for (i in dot + 1 until fracEnd) append(lexeme[i]) }
    }
}

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
/**
 * `(SELECT …)` as a value: one column, one row (NULL for none, pg's 21000 for more); with [array],
 * `ARRAY(SELECT …)`: the column's values as one array cell. A reference qualified by an outer FROM
 * item makes it correlated: the planner binds that row's cell before planning it.
 */
data class Subselect(val select: SelectStmt, val array: Boolean = false) : Expr
/** `left op ANY|SOME (right)` or `left op ALL (right)`: [right] is array-valued, an `ARRAY(SELECT …)`, a subselect read as its column, or an array cell. */
data class Quantified(val left: Expr, val op: String, val right: Expr, val all: Boolean = false) : Expr
/** A cell the planner bound into a statement (an outer row's value inside a correlated subselect); it has no source text of its own. */
class CellLiteral(val value: Any?) : SqlNode
data class Column(val expr: Expr, val alias: Identifier? = null) : SqlNode
/** A FROM item: a table, or with [args] a table function `name(args) [AS] alias` (pg's shape; `evoke_query(index, query, k)` and `unnest(array)` here). */
data class TableRef(val name: Identifier, val alias: Identifier? = null, val args: Series<Expr>? = null) : SqlNode
/** `[LEFT [OUTER] | INNER] JOIN table ON expr`; [on] null is `CROSS JOIN table` or the comma form, a Cartesian product. */
data class JoinClause(val table: TableRef, val on: Expr?, val outer: Boolean) : SqlNode
/** [nullsFirst] null is pg's default: NULLS LAST ascending, NULLS FIRST descending. */
data class OrderItem(val expr: Expr, val descending: Boolean, val nullsFirst: Boolean? = null) : SqlNode
/** A SELECT; [union] holds the `UNION [ALL]` arms (ALL as the flag), whose ORDER BY, LIMIT and OFFSET are this statement's. */
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
    val union: Series<Join<Boolean, SelectStmt>> = emptySeriesOf(),
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
            "NOT", "IN", "IS", "NULL", "TRUE", "FALSE", "LIKE", "ILIKE", "CASE", "WHEN", "THEN", "ELSE", "END",
            "ANY", "SOME", "ALL", "ARRAY",
        )

        /** pg's bare session words: a zero-argument call without parentheses. */
        val SESSION_WORDS = setOf("current_user", "session_user", "current_role", "current_schema", "current_catalog")

        /** The catalog schema pg clients qualify functions and types with; the names are the same here. */
        const val PG_CATALOG = "pg_catalog."

        /** The call names a CASE lowers to: the simple form with its operand first, the searched form without. */
        const val CASE = "case"
        const val CASE_WHEN = "case_when"

        /** pg's "any other operator" class, tighter than comparisons and left-associative: the regex and vector operators. */
        val OTHER_OPS = listOf("!~*", "!~", "~*", "~", "<=>", "<->", "<#>")

        fun parse(src: Series<Char>): SelectStmt? = SqlParser(CharSeries(src).trim).parseSelect()
        fun parse(text: String): SelectStmt? = parse(text.toSeries())

        private val LIKE_OPS = arrayOf("ILIKE", "LIKE")
        private val COMPARE_OPS = arrayOf("!=", "<>", ">=", "<=", "=", ">", "<", "@@")
    }

    fun skipWs() {
        while (cs.pos < cs.limit && cs[cs.pos].isWhitespace()) cs.pos++
    }

    fun peek(): Char = if (cs.pos < cs.limit) cs[cs.pos] else '\u0000'

    /** [keyword] is upper-case; the input is folded char by char, so no String is made per probe. */
    fun matchKeyword(keyword: String): Boolean {
        val save = cs.pos
        val k = keyword

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

    /**
     * `'text'` under standard_conforming_strings: a doubled quote `''` is one quote and a backslash is a
     * character. `E'text'` is pg's escape string, read by [parseEscapeString].
     */
    fun parseStringLiteral(): StringLiteral? {
        skipWs()
        if ((peek() == 'E' || peek() == 'e') && cs.pos + 1 < cs.limit && cs[cs.pos + 1] == '\'') { cs.pos++; return parseEscapeString() }
        if (peek() != '\'') return null
        var i = cs.pos + 1
        var doubled = false
        while (i < cs.limit) {
            val c = cs[i]
            when {
                c == '\'' && i + 1 < cs.limit && cs[i + 1] == '\'' -> { doubled = true; i += 2 }
                c == '\'' -> break
                else -> i++
            }
        }
        if (i >= cs.limit) return null
        val value = cs[cs.pos + 1 until i]
        cs.pos = i + 1
        if (!doubled) return StringLiteral(value)
        // the collapsed text is not contiguous in the source: one array, the second quote of each pair skipped
        val out = CharArray(value.size)
        var n = 0
        var k = 0
        while (k < value.size) { out[n++] = value[k]; k += if (value[k] == '\'') 2 else 1 }
        return StringLiteral(n j out::get)
    }

    /**
     * The body of `E'…'`, the cursor on the opening quote: `\b \f \n \r \t` as C reads them, `\\` and `\'`
     * themselves, any other escaped character itself, `''` one quote (psql's describe queries join with `E'\n'`).
     */
    fun parseEscapeString(): StringLiteral? {
        val out = StringBuilder()
        var i = cs.pos + 1
        while (i < cs.limit) {
            val c = cs[i]
            when {
                c == '\\' && i + 1 < cs.limit -> {
                    out.append(when (val e = cs[i + 1]) { 'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> e })
                    i += 2
                }
                c == '\'' && i + 1 < cs.limit && cs[i + 1] == '\'' -> { out.append('\''); i += 2 }
                c == '\'' -> { cs.pos = i + 1; return StringLiteral(out.toString().toSeries()) }
                else -> { out.append(c); i++ }
            }
        }
        return null
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
        return NumericLiteral(value as Number, if (fraction) numberText(slice) else null)
    }

    fun parseTerm(): Expr? {
        skipWs()
        if (peek() == '(') {
            cs.pos++
            skipWs()
            // `(SELECT …)`: a scalar subselect; anything else in parentheses is an expression
            val save = cs.pos
            if (matchKeyword("SELECT")) { cs.pos = save; return parsePostfix(Subselect(parseParenthesisedSelect() ?: return null)) }
            val e = parseExpr()
            skipWs()
            if (peek() == ')') cs.pos++
            return parsePostfix(e ?: return null)
        }
        // `ARRAY(SELECT …)`: the column as one array cell; `ARRAY[…]` literals are not read
        if (matchKeyword("ARRAY")) {
            skipWs()
            if (peek() != '(') return null
            cs.pos++
            return parsePostfix(Subselect(parseParenthesisedSelect() ?: return null, array = true))
        }
        parseStringLiteral()?.let { return parsePostfix(LitExpr(it)) }
        parseNumberLit()?.let { return parsePostfix(LitExpr(it)) }
        if (matchKeyword("NULL")) return parsePostfix(LitExpr(NullLiteral))
        if (matchKeyword("TRUE")) return parsePostfix(LitExpr(BooleanLiteral(true)))
        if (matchKeyword("FALSE")) return parsePostfix(LitExpr(BooleanLiteral(false)))
        if (matchKeyword("CASE")) return parsePostfix(parseCase() ?: return null)
        val quoted = peek() == '"'
        val id = parseIdentifier() ?: return null
        skipWs()
        if (peek() == '(') return parsePostfix(Call(id.name.catalogName(), parseArgs() ?: return null))
        // every unquoted column reference lands here: the length gate keeps the common case free of the word scan
        if (!quoted && id.name.size in 12..15) for (word in SESSION_WORDS) if (id.name.eqFold(word)) return parsePostfix(Call(word, emptySeriesOf()))
        if (peek() == ':' && !(cs.pos + 1 < cs.limit && cs[cs.pos + 1] == ':')) {
            cs.pos++
            return parsePostfix(Path(ColumnRef(id), parseColonPath(), text = false))
        }
        return parsePostfix(ColumnRef(id))
    }

    /** `(expr, …)` after a name, the cursor on `(`: a call's arguments; `(*)` is COUNT's star. Null when malformed. */
    fun parseArgs(): Series<Expr>? {
        cs.pos++
        skipWs()
        val args = SeriesBuffer<Expr>()
        if (peek() == '*') { cs.pos++; args += Star }
        else while (peek() != ')') {
            args += parseExpr() ?: return null
            skipWs()
            if (peek() == ',') { cs.pos++; skipWs() } else break
        }
        skipWs()
        if (peek() != ')') return null
        cs.pos++
        return args.drain()
    }

    /**
     * `->` paths, `::type` casts and `COLLATE name` (one collation here, so it is read and dropped), in any order:
     * `doc->>'n'::int`, `'[1,2]'::vector`, `c.oid::pg_catalog.regclass`, `relname COLLATE pg_catalog.default`.
     */
    fun parsePostfix(head: Expr): Expr {
        var left = parsePath(head)
        while (true) {
            val save = cs.pos
            skipWs()
            if (matchKeyword("COLLATE")) {
                skipWs()
                if (parseIdentifier() == null) { cs.pos = save; return left }
                continue
            }
            if (!matchSymbol("::")) { cs.pos = save; return left }
            skipWs()
            val type = parseIdentifier() ?: run { cs.pos = save; return left }
            left = parsePath(Cast(left, type.name.catalogName()))
        }
    }

    /**
     * `CASE operand WHEN v THEN r … [ELSE e] END` as `case(operand, v1, r1, …, else)` and the searched
     * `CASE WHEN c THEN r … [ELSE e] END` as `case_when(c1, r1, …, else)`; a missing ELSE is `NULL`. The
     * evaluator reads the arms in order and only as far as the first match, as pg does.
     */
    fun parseCase(): Expr? {
        skipWs()
        val args = SeriesBuffer<Expr>()
        val save = cs.pos
        val searched = matchKeyword("WHEN").also { cs.pos = save }
        if (!searched) args += parseExpr() ?: return null
        var arms = 0
        while (true) {
            skipWs()
            if (!matchKeyword("WHEN")) break
            args += parseExpr() ?: return null
            skipWs()
            if (!matchKeyword("THEN")) return null
            args += parseExpr() ?: return null
            arms++
        }
        if (arms == 0) return null
        skipWs()
        args += if (matchKeyword("ELSE")) parseExpr() ?: return null else LitExpr(NullLiteral)
        skipWs()
        if (!matchKeyword("END")) return null
        return Call(if (searched) CASE_WHEN else CASE, args.drain())
    }

    /** Snowflake `col:a.b[0]` segments after the colon. */
    fun parseColonPath(): JsPath {
        val steps = SeriesBuffer<JsPathElement>()
        while (cs.pos < cs.limit) {
            if (peek() == '[') {
                cs.pos++
                val start = cs.pos
                while (cs.pos < cs.limit && cs[cs.pos].isDigit()) cs.pos++
                steps += idxOf(cs[start until cs.pos].parseLong().toInt())
                if (peek() == ']') cs.pos++
            } else {
                if (peek() == '.') cs.pos++
                val start = cs.pos
                while (cs.pos < cs.limit && (cs[cs.pos].isLetterOrDigit() || cs[cs.pos] == '_')) cs.pos++
                if (start == cs.pos) break
                steps += keyOf(cs[start until cs.pos].asString())
            }
        }
        return steps.drain()
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
                var body = lit.value.trim()
                if (body.startsWith("{")) body = body.drop(1)
                if (body.endsWith("}")) body = body.slice(0, body.size - 1)
                val segs = body / ','
                val steps = SeriesBuffer<JsPathElement>()
                for (i in 0 until segs.size) {
                    val seg = segs[i].trim()
                    steps += seg.toIntOrNull()?.let { idxOf(it) } ?: keyOf(seg.asString())
                }
                steps.drain()
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
            left = BinaryExpr(left, opText(op), right)
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
            left = BinaryExpr(left, if (concat) "||" else opText(op), right)
        }
    }

    /**
     * pg's other operators, looser than `+ -` and tighter than comparisons: the regex matches `~ ~* !~ !~*` and
     * pgvector's `<=>` cosine distance, `<->` L2 distance, `<#>` negative inner product; also spelled
     * `OPERATOR([pg_catalog.] op)`, as psql writes `relname OPERATOR(pg_catalog.~) '^(x)$'`.
     */
    fun parseVector(): Expr? {
        var left = parseAdditive() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            val op = OTHER_OPS.firstOrNull { matchSymbol(it) } ?: parseOperatorSpelling() ?: run { cs.pos = save; return left }
            val right = parseAdditive() ?: run { cs.pos = save; return left }
            left = BinaryExpr(left, op, right)
        }
    }

    /** `OPERATOR([schema.]op)` naming one of [OTHER_OPS]; null, with the position kept, for anything else. */
    fun parseOperatorSpelling(): String? {
        val save = cs.pos
        if (!matchKeyword("OPERATOR")) return null
        skipWs()
        if (peek() != '(') { cs.pos = save; return null }
        cs.pos++
        skipWs()
        // An identifier here is the schema qualifier, read with its trailing dot (`pg_catalog.`); anything else is not this spelling.
        val qualifier = parseIdentifier()
        if (qualifier != null && !qualifier.name.endsWith(".")) { cs.pos = save; return null }
        skipWs()
        val op = OTHER_OPS.firstOrNull { matchSymbol(it) } ?: run { cs.pos = save; return null }
        skipWs()
        if (peek() != ')') { cs.pos = save; return null }
        cs.pos++
        return op
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
            val items = SeriesBuffer<Expr>()
            while (true) {
                items += parseExpr() ?: run { cs.pos = save; return left }
                skipWs()
                if (peek() == ',') cs.pos++ else break
            }
            if (peek() != ')') { cs.pos = save; return left }
            cs.pos++
            return InList(left, items.drain(), negated)
        }
        for (op in LIKE_OPS) if (matchKeyword(op)) {
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
        for (op in COMPARE_OPS) {
            if (!matchSymbol(op)) continue
            skipWs()
            // `op ANY|SOME (…)` / `op ALL (…)`: the right side is a subselect read as its column, or an array value
            val all = when { matchKeyword("ANY") || matchKeyword("SOME") -> false; matchKeyword("ALL") -> true; else -> null }
            if (all != null) {
                skipWs()
                if (peek() != '(') { cs.pos = save; return left }
                cs.pos++
                skipWs()
                val mark = cs.pos
                val right = if (matchKeyword("SELECT")) { cs.pos = mark; Subselect(parseParenthesisedSelect() ?: run { cs.pos = save; return left }, array = true) }
                else parseExpr()?.also { skipWs(); if (peek() != ')') { cs.pos = save; return left }; cs.pos++ } ?: run { cs.pos = save; return left }
                return Quantified(left, op, right, all)
            }
            val right = parsePattern() ?: run { cs.pos = save; return left }
            return BinaryExpr(left, op, right)
        }
        cs.pos = save
        return left
    }

    /** A SELECT whose closing parenthesis follows, the cursor after the opening one; the cursor ends after `)`. */
    fun parseParenthesisedSelect(): SelectStmt? {
        val stmt = parseSelectBody(tail = true) ?: return null
        skipWs()
        if (peek() != ')') return null
        cs.pos++
        return stmt
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
        val alias = parseAlias()
        return Column(expr as Expr, alias)
    }

    /** `[AS] alias`: after AS any identifier; bare, anything but a [RESERVED] word. The cursor stays put when there is none. */
    private fun parseAlias(): Identifier? {
        val save = cs.pos
        if (matchKeyword("AS")) {
            skipWs()
            return parseIdentifier().also { if (it == null) cs.pos = save }
        }
        val maybe = parseIdentifier()
        if (maybe == null || maybe.name.isReserved()) { cs.pos = save; return null }
        return maybe
    }

    fun parseSelectList(): Series<Column> {
        skipWs()
        val cols = SeriesBuffer<Column>()
        if (peek() == '*') {
            cs.pos++; cols += Column(LitExpr(StringLiteral("*".toSeries())), null); return cols.drain()
        }
        while (true) {
            val c = parseColumn() ?: break
            cols += c
            skipWs()
            if (peek() == ',') {
                cs.pos++; continue
            } else break
        }
        return cols.drain()
    }

    /** `table [[AS] alias]` or the table function `name(args) [[AS] alias]`. */
    fun parseTableRef(): TableRef? {
        skipWs()
        val name = parseIdentifier() ?: return null
        skipWs()
        val args = if (peek() == '(') parseArgs() ?: return null else null
        skipWs()
        val alias = parseAlias()
        return TableRef(name, alias, args)
    }

    /** The whole input as one statement: a SELECT, an optional `;`, nothing after. */
    fun parseSelect(): SelectStmt? {
        val stmt = parseSelectBody(tail = true) ?: return null
        skipWs()
        if (peek() == ';') cs.pos++
        skipWs()
        if (cs.pos < cs.limit) return null
        return stmt
    }

    /**
     * A SELECT up to where its text ends; with [tail], its `UNION [ALL]` arms (each read without a tail) and the
     * ORDER BY, LIMIT and OFFSET that belong to the whole; an arm or a subselect leaves them to its owner.
     */
    fun parseSelectBody(tail: Boolean): SelectStmt? {
        skipWs()
        if (!matchKeyword("SELECT")) return null
        skipWs()
        val cols = parseSelectList()
        skipWs()
        var from: TableRef? = null
        if (matchKeyword("FROM")) {
            skipWs()
            // FROM names a table or a table function; a malformed one (an unclosed argument list eats the rest) fails the statement
            from = parseTableRef() ?: return null
        }
        val joins = SeriesBuffer<JoinClause>()
        while (from != null) {
            val save = cs.pos
            skipWs()
            // `FROM a, b`: the comma form of a cross join
            if (peek() == ',') { cs.pos++; joins += JoinClause(parseTableRef() ?: return null, null, false); continue }
            val outer = matchKeyword("LEFT")
            val cross = !outer && matchKeyword("CROSS")
            if (outer) { skipWs(); matchKeyword("OUTER"); skipWs() } else if (cross) skipWs() else if (matchKeyword("INNER")) skipWs()
            if (!matchKeyword("JOIN")) { cs.pos = save; break }
            val table = parseTableRef() ?: return null
            skipWs()
            // a join reads its ON; CROSS JOIN takes none
            val on = if (cross) null else { if (!matchKeyword("ON")) return null; skipWs(); parseExpr() ?: return null }
            joins += JoinClause(table, on, outer)
        }
        skipWs()
        var where: Expr? = null
        if (matchKeyword("WHERE")) {
            skipWs()
            where = parseExpr()
        }
        skipWs()
        val groupBy = SeriesBuffer<Expr>()
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
        val union = SeriesBuffer<Join<Boolean, SelectStmt>>()
        if (tail) while (true) {
            val save = cs.pos
            if (!matchKeyword("UNION")) { cs.pos = save; break }
            skipWs()
            val all = matchKeyword("ALL")
            skipWs()
            union += all j (parseSelectBody(tail = false) ?: return null)
            skipWs()
        }
        if (!tail) return SelectStmt(cols, from, where, joins.drain(), groupBy.drain(), having)
        val orderBy = SeriesBuffer<OrderItem>()
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
        return SelectStmt(cols, from, where, joins.drain(), groupBy.drain(), having, orderBy.drain(), limit, offset, union.drain())
    }
}
