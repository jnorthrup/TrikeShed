package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.lib.*
import borg.trikeshed.collections._s

// Compact SQL AST using Series<Char> (zero-copy views)
// Canonical root — kursive, miniduck, and couch all consume from here.
sealed interface SqlNode

class Identifier(val name: Series<Char>) : SqlNode {
    fun asString(): CharSequence = name.asString()
    val s: CharSequence get() = asString()
}

class StringLiteral(val value: Series<Char>) : SqlNode {
    fun asString(): CharSequence = value.asString()
    val s: CharSequence get() = asString()
}

class NumericLiteral(val value: Number) : SqlNode

sealed interface Expr : SqlNode

class ColumnRef(val id: Identifier) : Expr
class LitExpr(val lit: SqlNode) : Expr
data class BinaryExpr(val left: Expr, val op: CharSequence, val right: Expr) : Expr
data class Column(val expr: Expr, val alias: Identifier? = null) : SqlNode
data class TableRef(val name: Identifier, val alias: Identifier? = null) : SqlNode
data class SelectStmt(val columns: Series<Column>, val from: TableRef?, val where: Expr?) : SqlNode

/**
 * Recursive-descent parser for a SELECT subset (SELECT ... FROM ... WHERE ...)
 * Works on CharSeries and avoids creating Strings until callers ask for them.
 */
class SqlParser(private val cs: CharSeries) {
    companion object {
        fun parse(src: Series<Char>): SelectStmt? = SqlParser(CharSeries(src).trim).parseSelect()
        fun parse(text: CharSequence): SelectStmt? {
            // try the recursive-descent parser first
            val textStr = text.toString()
            val parsed = parse(textStr.toSeries())
            if (parsed != null) return parsed

            // fallback
            val selUp = textStr.uppercase()
            val selIndex = selUp.indexOf("SELECT")
            val fromIndex = selUp.indexOf("FROM")
            if (selIndex < 0 || fromIndex < 0 || fromIndex <= selIndex) return null
            val colsPart = textStr.substring(selIndex + "SELECT".length, fromIndex).trim()
            val afterFrom = textStr.substring(fromIndex + "FROM".length).trim()
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
                    val op = cm.groupValues[2].uppercase()
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

    fun matchKeyword(keyword: CharSequence): Boolean {
        val save = cs.pos
        val k = keyword.toString().uppercase()
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
            if (!nc.isWhitespace() && nc != ',' && nc != '(' && nc != ')' && nc != ';') {
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
        if (cs.pos < cs.limit && cs[cs.pos] == '.') {
            cs.pos++
            while (cs.pos < cs.limit && cs[cs.pos].isDigit()) {
                cs.pos++; seen = true
            }
        }
        if (!seen) {
            cs.pos = start; return null
        }
        val slice: Series<Char> = cs[start until cs.pos]

        val num = slice.parseDoubleOrNull() ?: slice.parseLong()
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
            return e
        }
        parseStringLiteral()?.let { return LitExpr(it) }
        parseNumberLit()?.let { return LitExpr(it) }
        parseIdentifier()?.let { return ColumnRef(it) }
        return null
    }

    fun parseComparison(): Expr? {
        var left = parseTerm() ?: return null
        skipWs()
        val ops = listOf("!=", "<>", ">=", "<=", "=", ">", "<", "LIKE")
        for (op in ops) {
            val save = cs.pos
            var ok = true
            for (ch in op) {
                if (cs.pos >= cs.limit || cs[cs.pos].uppercaseChar() != ch.uppercaseChar()) {
                    ok = false; break
                }
                cs.pos++
            }
            if (!ok) {
                cs.pos = save; continue
            }
            skipWs()
            val right = parseTerm() ?: run { cs.pos = save; return left }
            left = BinaryExpr(left, op, right)
            break
        }
        return left
    }

    fun parseAnd(): Expr? {
        var left = parseComparison() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            if (matchKeyword("AND")) {
                skipWs()
                val right = parseComparison() ?: run { cs.pos = save; break }
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
        val save = cs.pos
        val expr = parseTerm() ?: return null
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
                val nameStr = maybe.name.asString().uppercase()
                val reserved =
                    _s["FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "JOIN", "ON", "AS", "UNION", "DISTINCT"]
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
                val nameStr = maybe.name.asString().uppercase()
                val reserved = setOf(
                    "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "JOIN", "ON", "AS", "UNION", "DISTINCT",
                )
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
        skipWs()
        var where: Expr? = null
        if (matchKeyword("WHERE")) {
            skipWs()
            where = parseExpr()
        }
        return SelectStmt(cols, from, where)
    }
}
