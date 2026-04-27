package borg.trikeshed.parse.jursive.sql

/**
 * Minimal SQL AST stubs for couch SQL integration.
 * These are structural types consumed by ExpressionCompiler, SqlToMiniDuck, etc.
 * The full kursive grammar will populate these via SqlParser.
 */

/** Top-level expression node */
sealed interface Expr

/** Column reference: SELECT t.col FROM ... */
data class ColumnRef(val id: String) : Expr

/** Literal value */
data class LitExpr(val lit: Any) : Expr

/** Binary expression: left op right */
data class BinaryExpr(val left: Expr, val right: Expr, val op: String) : Expr

/** String literal value */
data class StringLiteral(private val value: String) {
    fun asString(): String = value
}

/** Numeric literal value */
data class NumericLiteral(val value: Number) : Expr

/** Column in a select list */
data class SelectColumn(val expr: Expr, val alias: String? = null)

/** FROM clause table reference */
data class TableRef(val name: String, val alias: String? = null)

/** SELECT statement */
data class SelectStmt(
    val columns: List<SelectColumn>,
    val from: TableRef? = null,
    val where: Expr? = null
)

/** SQL parser entry point — basic string-based parser for common SELECT forms */
object SqlParser {
    private val simpleComp = Regex("(?i)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(=|!=|<>|>=|<=|>|<)\\s*('([^']*)'|\\d+(?:\\.\\d+)?)\\s*")

    private fun parseLiteral(token: String): Expr {
        val t = token.trim()
        return if (t.startsWith("'") && t.endsWith("'") && t.length >= 2) {
            LitExpr(StringLiteral(t.substring(1, t.length - 1)))
        } else {
            // numeric
            if (t.contains('.')) LitExpr(NumericLiteral(t.toDouble())) else LitExpr(NumericLiteral(t.toLong()))
        }
    }

    private fun parseWhereClause(where: String): Expr? {
        // very small subset: single comparison, optionally AND/OR combined
        val w = where.trim()
        // try parenthesis-free AND/OR split
        val orParts = w.split(Regex("(?i)\\s+OR\\s+"))
        if (orParts.size > 1) {
            val sub = orParts.mapNotNull { parseWhereClause(it) }
            if (sub.isEmpty()) return null
            var acc = sub[0]
            for (i in 1 until sub.size) acc = BinaryExpr(acc, sub[i], "OR")
            return acc
        }
        val andParts = w.split(Regex("(?i)\\s+AND\\s+"))
        if (andParts.size > 1) {
            val sub = andParts.mapNotNull { parseWhereClause(it) }
            if (sub.isEmpty()) return null
            var acc = sub[0]
            for (i in 1 until sub.size) acc = BinaryExpr(acc, sub[i], "AND")
            return acc
        }
        val m = simpleComp.matchEntire(w) ?: return null
        val col = m.groupValues[1]
        val op = m.groupValues[2].uppercase()
        val litRaw = m.groupValues[3]
        val right: Expr = if (litRaw.startsWith("'") && litRaw.endsWith("'")) {
            LitExpr(StringLiteral(litRaw.substring(1, litRaw.length - 1)))
        } else {
            if (litRaw.contains('.')) LitExpr(NumericLiteral(litRaw.toDouble())) else LitExpr(NumericLiteral(litRaw.toLong()))
        }
        return BinaryExpr(ColumnRef(col), right, op)
    }

    fun parse(sql: String): SelectStmt? {
        val text = sql.trim()
        val up = text.uppercase()
        val selIndex = up.indexOf("SELECT")
        val fromIndex = up.indexOf("FROM")
        if (selIndex < 0) return null
        if (fromIndex < 0 || fromIndex <= selIndex) return null
        val colsPart = text.substring(selIndex + "SELECT".length, fromIndex).trim()
        val afterFrom = text.substring(fromIndex + "FROM".length).trim()
        val parts = afterFrom.split(Regex("\\s+WHERE\\s+", RegexOption.IGNORE_CASE), 2)
        val tablePart = parts[0].trim().split(Regex("\\s+"))[0]
        val wherePart = if (parts.size > 1) parts[1].trim() else null

        val cols: List<SelectColumn> = if (colsPart == "*") {
            listOf(SelectColumn(LitExpr(StringLiteral("*")), null))
        } else {
            colsPart.split(',').map { c -> SelectColumn(ColumnRef(c.trim()), null) }
        }

        val whereExpr = wherePart?.let { parseWhereClause(it) }
        return SelectStmt(cols, TableRef(tablePart), whereExpr)
    }
}
