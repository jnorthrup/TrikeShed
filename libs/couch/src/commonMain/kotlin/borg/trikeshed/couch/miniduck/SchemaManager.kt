package borg.trikeshed.couch.miniduck.schema

/**
 * Minimal schema types and manager interface for initial auto-DDL support.
 * Placed under package "...schema" so other code can import borg.trikeshed.couch.miniduck.schema.*
 */

data class ColumnSchema(val id: Int, val name: String)
data class TableSchema(val name: String, val columns: List<ColumnSchema>)

interface SchemaManager {
    fun getTable(name: String): TableSchema?
    fun createTable(schema: TableSchema)
    fun ensureColumns(table: String, cols: List<String>): TableSchema
}
