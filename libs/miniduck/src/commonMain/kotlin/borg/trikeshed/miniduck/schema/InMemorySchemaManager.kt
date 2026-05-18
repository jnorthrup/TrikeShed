package borg.trikeshed.miniduck.schema

class InMemorySchemaManager : SchemaManager {
    private val tables = linkedMapOf<String, TableSchema>()

    override fun getTable(name: String): TableSchema? = tables[name]

    override fun createTable(schema: TableSchema) {
        tables[schema.name] = schema
    }

    override fun ensureColumns(table: String, cols: List<String>): TableSchema {
        return tables.getOrPut(table) {
            TableSchema(table, cols.mapIndexed { index, name -> ColumnSchema(index, name) })
        }
    }
}
