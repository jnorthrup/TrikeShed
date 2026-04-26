package borg.trikeshed.miniduck.schema

import borg.trikeshed.userspace.database.LsmrDatabase
/**
 * LSMR-backed SchemaManager storing simple table->columns metadata in the LSMR keyspace.
 * Stored value format: "{tableName}|col1,col2,col3"
 */
class LsmrSchemaManager(private val db: LsmrDatabase) : SchemaManager {

    private fun keyForTable(name: String) = "miniduck:schema:$name"

    override suspend fun getTableSuspend(name: String): TableSchema? {
        val raw = db.get(keyForTable(name)) ?: return null
        val s = raw.decodeToString()
        val parts = s.split('|', limit = 2)
        val colsPart = if (parts.size > 1) parts[1] else parts[0]
        if (colsPart.isEmpty()) return TableSchema(name, emptyList())
        val cols = colsPart.split(',').mapIndexed { idx, nm -> ColumnSchema(idx, nm) }
        return TableSchema(name, cols)
    }

    override suspend fun createTableSuspend(schema: TableSchema) {
        val cols = schema.columns.joinToString(",") { it.name }
        val s = "${schema.name}|$cols"
        db.put(keyForTable(schema.name), s.encodeToByteArray())
    }

    override suspend fun ensureColumnsSuspend(table: String, cols: List<String>): TableSchema {
        val existing = getTableSuspend(table)
        if (existing == null) {
            val newCols = cols.mapIndexed { i, n -> ColumnSchema(i, n) }
            val ts = TableSchema(table, newCols)
            createTableSuspend(ts)
            return ts
        }
        val existingNames = existing.columns.map { it.name }.toMutableList()
        var nextId = (existing.columns.maxByOrNull { it.id }?.id ?: -1) + 1
        val added = cols.filter { !existingNames.contains(it) }.map {
            ColumnSchema(nextId++, it)
        }
        if (added.isNotEmpty()) {
            val newSchema = TableSchema(table, existing.columns + added)
            createTableSuspend(newSchema)
            return newSchema
        }
        return existing
    }
}
