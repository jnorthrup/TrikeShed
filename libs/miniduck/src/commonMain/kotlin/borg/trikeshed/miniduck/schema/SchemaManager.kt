package borg.trikeshed.miniduck.schema

interface SchemaManager {
    fun getTable(name: CharSequence): TableSchema?

    suspend fun getTableSuspend(name: CharSequence): TableSchema? = getTable(name)

    fun createTable(schema: TableSchema) {}

    suspend fun createTableSuspend(schema: TableSchema) {
        createTable(schema)
    }

    fun ensureColumns(table: CharSequence, cols: List<CharSequence>): TableSchema =
        TableSchema(table, cols.mapIndexed { index, name -> ColumnSchema(index, name) })

    suspend fun ensureColumnsSuspend(table: CharSequence, cols: List<CharSequence>): TableSchema = ensureColumns(table, cols)
}
