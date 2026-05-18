package borg.trikeshed.miniduck.schema

interface SchemaManager {
    fun getTable(name: String): TableSchema?

    suspend fun getTableSuspend(name: String): TableSchema? = getTable(name)

    fun createTable(schema: TableSchema) {}

    suspend fun createTableSuspend(schema: TableSchema) {
        createTable(schema)
    }

    fun ensureColumns(table: String, cols: List<String>): TableSchema =
        TableSchema(table, cols.mapIndexed { index, name -> ColumnSchema(index, name) })

    suspend fun ensureColumnsSuspend(table: String, cols: List<String>): TableSchema = ensureColumns(table, cols)
}
