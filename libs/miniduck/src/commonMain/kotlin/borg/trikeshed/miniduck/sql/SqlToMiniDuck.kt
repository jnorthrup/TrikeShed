package borg.trikeshed.miniduck.sql

import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.plan.*
import borg.trikeshed.miniduck.schema.SchemaManager
import borg.trikeshed.miniduck.exec.RowAccessor
import borg.trikeshed.miniduck.exec.Cursor
import borg.trikeshed.miniduck.exec.ExecutionContext

/**
 * Planner configuration and entry point for SQL → MiniDuck transform.
 */

data class PlannerConfig(val autoCreateSchema: Boolean = true)
class PlannerContext(val schemaManager: SchemaManager, val config: PlannerConfig = PlannerConfig())

fun transformSelect(stmt: SelectStmt, ctx: PlannerContext): PlanNode {
    // Support queries without FROM (e.g., SELECT 1) by providing a single-row source.
    var node: PlanNode = if (stmt.from == null) {
        object : PlanNode {
            override fun open(execCtx: ExecutionContext): Cursor {
                return object : Cursor {
                    var emitted = false
                    override fun next(): Boolean {
                        if (emitted) return false
                        emitted = true
                        return true
                    }

                    override val row: RowAccessor
                        get() = object : RowAccessor {
                            override fun get(index: Int): Any? = null
                            override fun get(name: String): Any? = null
                        }

                    override fun close() {}
                }
            }
        }
    } else {
        val from = stmt.from!!
        val tableName = from.name
        TableScanNode(tableName, from.alias)
    }

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
            val name = col.alias ?: when (val e = col.expr) {
                is ColumnRef -> e.id
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
