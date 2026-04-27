package borg.trikeshed.miniduck.schema

import borg.trikeshed.miniduck.runBlockingCommon

/**
 * An in-memory implementation of SchemaManager for simple usage.
 */
class InMemorySchemaManager : SchemaManager {
   val tables = mutableMapOf<String, TableSchema>()

    // suspend implementations for async interface
    override suspend fun getTableSuspend(name: String): TableSchema? = tables[name]

    override suspend fun createTableSuspend(schema: TableSchema) {
        tables[schema.name] = schema
    }

    override suspend fun ensureColumnsSuspend(table: String, cols: List<String>): TableSchema =
        run {
            val existing = tables[table]
            if (existing == null) {
                val newCols = cols.mapIndexed { i, n -> ColumnSchema(i, n) }
                val ts = TableSchema(table, newCols)
                tables[table] = ts
                return ts
            }
            val existingNames = existing.columns.map { it.name }.toMutableList()
            var nextId = (existing.columns.maxByOrNull { it.id }?.id ?: -1) + 1
            val added = cols.filter { !existingNames.contains(it) }.map {
                ColumnSchema(nextId++, it)
            }
            if (added.isNotEmpty()) {
                tables[table] = TableSchema(table, existing.columns + added)
            }
            return tables[table]!!
        }

    // backward-compatible synchronous wrappers
    override fun getTable(name: String): TableSchema? = tables[name]

    override fun createTable(schema: TableSchema) { tables[schema.name] = schema }

    override fun ensureColumns(table: String, cols: List<String>): TableSchema = runBlockingCommon { ensureColumnsSuspend(table, cols) }
}
