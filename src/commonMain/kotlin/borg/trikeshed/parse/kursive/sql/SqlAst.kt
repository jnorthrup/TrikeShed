package borg.trikeshed.parse.kursive.sql

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

/** SQL parser entry point — stub until kursive grammar is implemented */
object SqlParser {
    fun parse(sql: String): SelectStmt? = null
}
