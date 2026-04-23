package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.asString
import borg.trikeshed.lib.get
import borg.trikeshed.lib.parseDoubleOrNull
import borg.trikeshed.lib.parseLong
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

// Compact SQL AST and parser using CharSeries (zero-copy views)
sealed interface SqlNode

data class Identifier(val name: Series<Char>) : SqlNode {
    fun asString(): String = name.asString()
}

data class StringLiteral(val value: Series<Char>) : SqlNode {
    fun asString(): String = value.asString()
}

data class NumericLiteral(val value: Number) : SqlNode

sealed interface Expr : SqlNode

data class ColumnRef(val id: Identifier) : Expr
data class LitExpr(val lit: SqlNode) : Expr
data class BinaryExpr(val left: Expr, val op: String, val right: Expr) : Expr

data class Column(val expr: Expr, val alias: Identifier? = null) : SqlNode
data class TableRef(val name: Identifier, val alias: Identifier? = null) : SqlNode
data class SelectStmt(val columns: List<Column>, val from: TableRef?, val where: Expr?) : SqlNode

/**
 * Small recursive-descent parser for a SELECT subset (SELECT ... FROM ... WHERE ...)
 * Works on CharSeries and avoids creating Strings until callers ask for them.
 */
class SqlParser(private val cs: CharSeries) {
    companion object {
        fun parse(src: Series<Char>): SelectStmt? = SqlParser(CharSeries(src).trim).parseSelect()
        fun parse(text: String): SelectStmt? = parse(text.toSeries())
    }

    private fun skipWs() {
        while (cs.pos < cs.limit && cs[cs.pos].isWhitespace()) cs.pos++
    }

    private fun peek(): Char = if (cs.pos < cs.limit) cs[cs.pos] else '\u0000'

    private fun matchKeyword(keyword: String): Boolean {
        val save = cs.pos
        val k = keyword.uppercase()
        for (i in k.indices) {
            if (cs.pos + i >= cs.limit) { cs.pos = save; return false }
            if (cs[cs.pos + i].uppercaseChar() != k[i]) { cs.pos = save; return false }
        }
        val next = cs.pos + k.length
        if (next < cs.limit) {
            val nc = cs[next]
            if (!nc.isWhitespace() && nc != ',' && nc != '(' && nc != ')' && nc != ';') { cs.pos = save; return false }
        }
        cs.pos += k.length
        return true
    }

    private fun parseIdentifier(): Identifier? {
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

    private fun parseStringLiteral(): StringLiteral? {
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

    private fun parseNumberLit(): NumericLiteral? {
        skipWs()
        val start = cs.pos
        var seen = false
        if (cs.pos < cs.limit && (cs[cs.pos] == '+' || cs[cs.pos] == '-')) cs.pos++
        while (cs.pos < cs.limit && cs[cs.pos].isDigit()) { cs.pos++; seen = true }
        if (cs.pos < cs.limit && cs[cs.pos] == '.') {
            cs.pos++
            while (cs.pos < cs.limit && cs[cs.pos].isDigit()) { cs.pos++; seen = true }
        }
        if (!seen) { cs.pos = start; return null }
        val slice: Series<Char> = cs[start until cs.pos]

        val num  = slice.parseDoubleOrNull() ?:slice.parseLong()
        val value = when (num) {
            is Long -> if (num in Int.MIN_VALUE..Int.MAX_VALUE) num.toInt() else num
            is Double -> num
            else -> num
        }
        return NumericLiteral(value as Number)
    }

    private fun parseTerm(): Expr? {
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

    private fun parseComparison(): Expr? {
        var left = parseTerm() ?: return null
        skipWs()
        val ops = listOf("!=", "<>", ">=", "<=", "=", ">", "<", "LIKE")
        for (op in ops) {
            val save = cs.pos
            var ok = true
            for (ch in op) {
                if (cs.pos >= cs.limit || cs[cs.pos].uppercaseChar() != ch.uppercaseChar()) { ok = false; break }
                cs.pos++
            }
            if (!ok) { cs.pos = save; continue }
            skipWs()
            val right = parseTerm() ?: run { cs.pos = save; return left }
            left = BinaryExpr(left, op, right)
            break
        }
        return left
    }

    private fun parseAnd(): Expr? {
        var left = parseComparison() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            if (matchKeyword("AND")) {
                skipWs()
                val right = parseComparison() ?: run { cs.pos = save; break }
                left = BinaryExpr(left, "AND", right)
            } else { cs.pos = save; break }
        }
        return left
    }

    private fun parseExpr(): Expr? {
        var left = parseAnd() ?: return null
        while (true) {
            val save = cs.pos
            skipWs()
            if (matchKeyword("OR")) {
                skipWs()
                val right = parseAnd() ?: run { cs.pos = save; break }
                left = BinaryExpr(left, "OR", right)
            } else { cs.pos = save; break }
        }
        return left
    }

    private fun parseColumn(): Column? {
        skipWs()
        val save = cs.pos
        val expr = parseTerm() ?: return null
        skipWs()
        var alias: Identifier? = null
        val save2 = cs.pos
        if (matchKeyword("AS")) {
            skipWs()
            alias = parseIdentifier()
            if (alias == null) { cs.pos = save2 }
        } else {
            val maybe = parseIdentifier()
            if (maybe != null) alias = maybe
            else cs.pos = save2
        }
        return Column(expr as Expr, alias)
    }

    private fun parseSelectList(): List<Column> {
        skipWs()
        val cols = mutableListOf<Column>()
        if (peek() == '*') { cs.pos++ ; cols += Column(LitExpr(StringLiteral("*".toSeries())), null); return cols }
        while (true) {
            val c = parseColumn() ?: break
            cols += c
            skipWs()
            if (peek() == ',') { cs.pos++; continue } else break
        }
        return cols
    }

    private fun parseTableRef(): TableRef? {
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
            if (maybe != null) alias = maybe
            else cs.pos = save
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
