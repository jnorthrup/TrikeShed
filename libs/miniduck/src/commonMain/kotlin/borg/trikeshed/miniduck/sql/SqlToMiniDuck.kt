package borg.trikeshed.miniduck.sql

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.view
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.exec.Cursor
import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.exec.RowAccessor
import borg.trikeshed.miniduck.exec.SeriesCursor
import borg.trikeshed.miniduck.exec.TableSource
import borg.trikeshed.miniduck.schema.SchemaManager
import borg.trikeshed.miniduck.schema.TableSchema
import borg.trikeshed.parse.kursive.sql.BinaryExpr
import borg.trikeshed.parse.kursive.sql.Column
import borg.trikeshed.parse.kursive.sql.ColumnRef
import borg.trikeshed.parse.kursive.sql.Expr
import borg.trikeshed.parse.kursive.sql.LitExpr
import borg.trikeshed.parse.kursive.sql.NumericLiteral
import borg.trikeshed.parse.kursive.sql.SelectStmt
import borg.trikeshed.parse.kursive.sql.StringLiteral

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
            is BinaryExpr -> when (where.op) {
                "=", "==" -> valuesEqual(valueOf(where.left, row), valueOf(where.right, row))
                else -> true
            }
            else -> true
        }
    }

    private fun valueOf(expr: Expr, row: RowAccessor): Any? = when (expr) {
        is ColumnRef -> row[expr.id.asString()]
        is LitExpr -> when (val lit = expr.lit) {
            is StringLiteral -> lit.asString()
            is NumericLiteral -> lit.value
            else -> null
        }
        is BinaryExpr -> null
        else -> null
    }

    private fun valuesEqual(left: Any?, right: Any?): Boolean {
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        return left == right
    }

    private fun projectRow(
        row: RowAccessor,
        columns: borg.trikeshed.lib.Series<Column>,
        schema: TableSchema?,
    ): DocRowVec {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        if (isSelectAll(columns)) {
            val schemaColumns = schema?.columns ?: emptyList()
            for (column in schemaColumns) {
                names.add(column.name)
                values.add(row[column.name])
            }
            return DocRowVec(names, values)
        }

        for (column in columns.view) {
            val expr = column.expr
            val alias = column.alias?.asString()
            when (expr) {
                is ColumnRef -> {
                    val name = alias ?: expr.id.asString()
                    names.add(name)
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
