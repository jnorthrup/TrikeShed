package borg.trikeshed.couch.miniduck.sql

import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.lib.*
import borg.trikeshed.couch.miniduck.plan.*
import borg.trikeshed.couch.miniduck.schema.SchemaManager
import borg.trikeshed.couch.miniduck.exec.RowAccessor

/**
 * Planner configuration and entry point for SQL → MiniDuck transform.
 */

data class PlannerConfig(val autoCreateSchema: Boolean = true)
class PlannerContext(val schemaManager: SchemaManager, val config: PlannerConfig = PlannerConfig())

fun transformSelect(stmt: SelectStmt, ctx: PlannerContext): PlanNode {
    val from = stmt.from ?: throw IllegalArgumentException("Only queries with a single FROM table are supported")
    val tableName = from.name.asString()
    var node: PlanNode = TableScanNode(tableName, from.alias?.asString())

    // WHERE -> FilterNode
    stmt.where?.let { expr ->
        val pred = compilePredicate(expr, ctx)
        node = FilterNode(node, pred)
    }

    // Projection -> ProjectNode (unless SELECT *)
    var isStar = false
    for (col in stmt.columns) {
        if (col.expr is LitExpr) {
            val lit = (col.expr as LitExpr).lit
            if (lit is StringLiteral && lit.asString() == "*") {
                isStar = true
                break
            }
        }
    }

    if (!isStar) {
        val projections = ArrayList<(RowAccessor) -> Any?>()
        val names = ArrayList<String>()
        for (col in stmt.columns) {
            projections.add(compileExpression(col.expr, ctx))
            val name = col.alias?.asString() ?: when (val e = col.expr) {
                is ColumnRef -> e.id.asString()
                is LitExpr -> {
                    val lit = e.lit
                    if (lit is StringLiteral) lit.asString() else "expr"
                }
                else -> "expr"
            }
            names.add(name)
        }
        node = ProjectNode(node, projections, names)
    }

    return node
}
