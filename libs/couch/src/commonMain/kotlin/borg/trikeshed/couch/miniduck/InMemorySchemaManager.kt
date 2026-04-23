package borg.trikeshed.couch.miniduck.schema

/**
 * An in-memory implementation of SchemaManager for simple usage.
 */
class InMemorySchemaManager : SchemaManager {
    private val tables = mutableMapOf<String, TableSchema>()

    override fun getTable(name: String): TableSchema? = tables[name]

    override fun createTable(schema: TableSchema) {
        tables[schema.name] = schema
    }

    override fun ensureColumns(table: String, cols: List<String>): TableSchema {
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
}