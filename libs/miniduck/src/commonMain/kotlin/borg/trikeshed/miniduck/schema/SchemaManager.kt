package borg.trikeshed.miniduck.schema

import borg.trikeshed.miniduck.runBlockingCommon

/**
 * Minimal schema types and manager interface for initial auto-DDL support.
 * Placed under package "...schema" so other code can import borg.trikeshed.miniduck.schema.*
 */

data class ColumnSchema(val id: Int = -1, val name: String, val type: borg.trikeshed.miniduck.columnar.ColumnType = borg.trikeshed.miniduck.columnar.ColumnType.Long, val indexPluginName: String? = null) { init { require(name.isNotEmpty()) { "ColumnSchema name must not be empty" } } }
data class TableSchema(val name: String, val columns: List<ColumnSchema>)

interface SchemaManager {
    // Suspend variants for async backends (LSMR). Implementers should provide suspend implementations.
    suspend fun getTableSuspend(name: String): TableSchema?
    suspend fun createTableSuspend(schema: TableSchema)
    suspend fun ensureColumnsSuspend(table: String, cols: List<String>): TableSchema

    // Backwards-compatible synchronous wrappers that bridge to suspend variants.
    fun getTable(name: String): TableSchema? = runBlockingCommon { getTableSuspend(name) }
    fun createTable(schema: TableSchema) { runBlockingCommon { createTableSuspend(schema) } }
    fun ensureColumns(table: String, cols: List<String>): TableSchema = runBlockingCommon { ensureColumnsSuspend(table, cols) }
}
