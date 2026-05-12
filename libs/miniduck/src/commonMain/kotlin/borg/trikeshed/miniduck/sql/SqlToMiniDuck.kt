package borg.trikeshed.miniduck.sql

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.exec.*
import borg.trikeshed.miniduck.schema.SchemaManager
import borg.trikeshed.miniduck.schema.TableSchema
import borg.trikeshed.parse.kursive.sql.*

data class PlannerContext(
    val schemaManager: SchemaManager? = null,
    val config: PlannerConfig = PlannerConfig(),
)

data class SelectPlan(
    private val stmt: SelectStmt,
    private val planner: PlannerContext,
) {
    fun open(execCtx: ExecutionContext): Cursor {
        val tableName = stmt.from?.name?.asString()
            ?: error("SELECT without FROM is not supported")
        val tableSource = execCtx.tableSource as? TableSource
            ?: error("ExecutionContext.tableSource must implement borg.trikeshed.miniduck.exec.TableSource")

        val base = tableSource.open(execCtx, tableName)
        val schemaManager = planner.schemaManager ?: (execCtx.schemaManager as? SchemaManager)
        val schema = schemaManager?.getTable(tableName)

        if (stmt.where == null && isSelectAll(stmt.columns) && schema == null) {
            return base
        }

        val rows = mutableListOf<DocRowVec>()
        while (base.next()) {
            val current = base.row
            if (!matchesWhere(current, stmt.where)) continue

            val projection = projectRow(current, stmt.columns, schema)
            rows.add(projection)
        }
        base.close()
        return SeriesCursor(rows.size j { idx: Int -> rows[idx] as RowVec })
    }

    private fun isSelectAll(columns: borg.trikeshed.lib.Series<Column>): Boolean {
        return columns.size == 1 && columns[0].expr is LitExpr &&
            (columns[0].expr as LitExpr).lit is StringLiteral &&
            ((columns[0].expr as LitExpr).lit as StringLiteral).asString() == "*"
    }

    private fun matchesWhere(row: RowAccessor, where: Expr?): Boolean {
        if (where == null) return true
        return when (where) {
            is BinaryExpr -> when (where.op.toString().uppercase()) {
                "AND" -> matchesWhere(row, where.left) && matchesWhere(row, where.right)
                "OR" -> matchesWhere(row, where.left) || matchesWhere(row, where.right)
                "=", "==" -> valuesEqual(valueOf(where.left, row), valueOf(where.right, row))
                "!=", "<>" -> !valuesEqual(valueOf(where.left, row), valueOf(where.right, row))
                ">" -> compareValues(valueOf(where.left, row), valueOf(where.right, row))?.let { it > 0 } ?: false
                ">=" -> compareValues(valueOf(where.left, row), valueOf(where.right, row))?.let { it >= 0 } ?: false
                "<" -> compareValues(valueOf(where.left, row), valueOf(where.right, row))?.let { it < 0 } ?: false
                "<=" -> compareValues(valueOf(where.left, row), valueOf(where.right, row))?.let { it <= 0 } ?: false
                else -> false
            }
            is LitExpr -> valueOf(where, row) as? Boolean ?: false
            else -> false
        }
    }

    private fun valueOf(expr: Expr, row: RowAccessor): Any? = when (expr) {
        is ColumnRef -> row[expr.id.asString()]
        is LitExpr -> when (val lit = expr.lit) {
            is StringLiteral -> lit.asString()
            is NumericLiteral -> lit.value
            else -> null
        }
        is BinaryExpr -> matchesWhere(row, expr)
    }

    private fun valuesEqual(left: Any?, right: Any?): Boolean {
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        return left == right
    }

    private fun compareValues(left: Any?, right: Any?): Int? = when {
        left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
        left is CharSequence && right is CharSequence -> left.toString().compareTo(right.toString())
        left is Boolean && right is Boolean -> left.compareTo(right)
        else -> null
    }

    private fun projectRow(
        row: RowAccessor,
        columns: borg.trikeshed.lib.Series<Column>,
        schema: TableSchema?,
    ): DocRowVec {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        if (isSelectAll(columns)) {
            val schemaColumns = schema?.columns
            if (schemaColumns != null) {
                for (column in schemaColumns) {
                    names.add(column.name.toString())
                    values.add(row[column.name])
                }
            } else {
                for (index in 0 until row.size) {
                    names.add(row.columnName(index)?.toString() ?: "col$index")
                    values.add(row[index])
                }
            }
            return DocRowVec(names, values)
        }

        for (column in columns.view) {
            val expr = column.expr
            val alias: String? = column.alias?.asString()?.toString()
            when (expr) {
                is ColumnRef -> {
                    val name = alias ?: expr.id.asString().toString()
                    names.add(name )
                    values.add(row[expr.id.asString()])
                }
                is LitExpr -> {
                    val name = alias ?: "literal_${names.size}"
                    names.add(name)
                    values.add(valueOf(expr, row))
                }
                else -> {
                    val name = alias ?: "expr_${names.size}"
                    names.add(name)
                    values.add(null)
                }
            }
        }

        return DocRowVec(names, values)
    }
}

fun transformSelect(stmt: SelectStmt, context: PlannerContext): SelectPlan = SelectPlan(stmt, context)
