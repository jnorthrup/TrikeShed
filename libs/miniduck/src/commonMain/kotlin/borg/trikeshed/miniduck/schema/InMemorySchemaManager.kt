package borg.trikeshed.miniduck.schema

class InMemorySchemaManager : SchemaManager {
    private val tables = linkedMapOf<CharSequence, TableSchema>()

    override fun getTable(name: CharSequence): TableSchema? = tables[name]

    override fun createTable(schema: TableSchema) {
        tables[schema.name] = schema
    }

    override fun ensureColumns(table: CharSequence, cols: List<CharSequence>): TableSchema {
        return tables.getOrPut(table) {
            TableSchema(table, cols.mapIndexed { index, name -> ColumnSchema(index, name) })
        }
    }
}
