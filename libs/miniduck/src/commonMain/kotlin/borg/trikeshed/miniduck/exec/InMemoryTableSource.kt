package borg.trikeshed.miniduck.exec

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.cells
import borg.trikeshed.cursor.keys
import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.schema.SchemaManager
import borg.trikeshed.miniduck.schema.TableSchema

/** Simple in-memory table source used for tests and JS platform stub delegation. */
class InMemoryTableSource : TableSource {
    private val tables: MutableMap<String, MutableSeries<RowVec>> = linkedMapOf()
    private val schemas: MutableMap<String, TableSchema> = linkedMapOf()

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        val rows: Series<RowVec> = tables[tableName] ?: emptySeries()
        val schema = (execCtx.schemaManager as? SchemaManager)?.getTable(tableName) ?: schemas[tableName]
        // For schema-based rows with column names, transform to the schema columns.
        // For seed rows (no column names), return as-is.
        val rowVecs: Series<RowVec> = rows.size j { index: Int ->
            val row = rows[index]
            if (schema != null && row.keys.size > 0) {
                // Transform to schema's column keys
                val currentCells = row.cells
                val keys = schema.columns.size j { i: Int -> schema.columns[i].name }
                DocRowVec(keys, currentCells)
            } else {
                row
            }
        }
        return SeriesCursor(rowVecs)
    }

    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor = open(execCtx, tableName)

    override fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>) {
        val list = tables.getOrPut(tableName) { emptySeries<RowVec>().cow }
        val schema = schemas[tableName]
        val keys = if (schema != null) {
            schema.columns.size j { i: Int -> schema.columns[i].name }
        } else {
            row.size j { i: Int -> "col$i" }
        }
        list.add(DocRowVec(keys, seriesOf(row)))
    }

    override suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>) = insert(execCtx, tableName, row)

    override fun seedRows(tableName: String, rows: List<List<Any?>>) {
        if (rows.isEmpty()) return
        // Infer column names from first row if present
        val firstRow = rows[0]
        val colCount = firstRow.size
        val keys = colCount j { i: Int -> "col$i" }
        val list = tables.getOrPut(tableName) { emptySeries<RowVec>().cow }
        rows.forEach { r ->
            list.add(DocRowVec(keys, seriesOf(r)))
        }
    }

    fun addTable(schema: TableSchema, rows: List<List<Any?>>) {
        schemas[schema.name] = schema
        val list = tables.getOrPut(schema.name) { emptySeries<RowVec>().cow }
        val keys = schema.columns.size j { i: Int -> schema.columns[i].name }
        rows.forEach { row ->
            list.add(DocRowVec(keys, seriesOf(row)))
        }
    }
}
