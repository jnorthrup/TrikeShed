package borg.trikeshed.miniduck.exec

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.toRowVec
import borg.trikeshed.miniduck.schema.SchemaManager
import borg.trikeshed.miniduck.schema.TableSchema

/** Simple in-memory table source used for tests and JS platform stub delegation. */
class InMemoryTableSource : TableSource {
    private val tables: MutableMap<String, MutableSeries<DocRowVec>> = linkedMapOf()
    private val schemas: MutableMap<String, TableSchema> = linkedMapOf()

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        val rows: Series<DocRowVec> = tables[tableName] ?: emptySeries()
        val schema = (execCtx.schemaManager as? SchemaManager)?.getTable(tableName) ?: schemas[tableName]
        val rowVecs: Series<borg.trikeshed.cursor.RowVec> = rows.size j { index: Int ->
            val row = rows[index]
            if (schema != null && row.keys.isEmpty()) {
                DocRowVec(schema.columns.map { it.name }, row.cells.toList())
            } else {
                row
            }
        }
        return SeriesCursor(rowVecs)
    }

    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor = open(execCtx, tableName)

    override fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>) {
        val list = tables.getOrPut(tableName) { emptySeries<DocRowVec>().cow }
        val schema = schemas[tableName]
        val keys = schema?.columns?.map { it.name } ?: emptySeries<String>().toList()
        list.add(DocRowVec(keys, row))
    }

    override suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>) = insert(execCtx, tableName, row)

    override fun seedRows(tableName: String, rows: List<List<Any?>>) {
        val list = tables.getOrPut(tableName) { emptySeries<DocRowVec>().cow }
        rows.forEach { r -> list.add(DocRowVec(emptySeries<String>(), r.toSeries())) }
    }

    fun addTable(schema: TableSchema, rows: List<List<Any?>>) {
        schemas[schema.name] = schema
        val list = tables.getOrPut(schema.name) { emptySeries<DocRowVec>().cow }
        val keys = schema.columns.map { it.name }
        rows.forEach { row -> list.add(DocRowVec(keys, row)) }
    }
}
