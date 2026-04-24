package borg.trikeshed.couch.miniduck.exec

import borg.trikeshed.userspace.database.LsmrDatabase

import borg.trikeshed.couch.miniduck.schema.TableSchema

/**
 * TableSource backed by the LsmrDatabase. Simple row serialization for primitives and strings.
 * Keys used:
 *  - miniduck:table:{table}:count -> number of rows (as UTF-8 decimal)
 *  - miniduck:table:{table}:row:{idx} -> serialized row bytes
 */
class LsmrTableSource(private val db: LsmrDatabase) : TableSource {

    private fun countKey(table: String) = "miniduck:table:$table:count"
    private fun rowKey(table: String, idx: Int) = "miniduck:table:$table:row:$idx"

    private fun serializeRow(row: List<Any?>): ByteArray {
        val parts = row.map { v ->
            when (v) {
                null -> "N"
                is Int -> "I${v}"
                is Long -> "L${v}"
                is Double -> "D${v}"
                is Float -> "F${v}"
                is String -> "S${v.length}:${v}"
                else -> "S${v.toString().length}:${v.toString()}"
            }
        }
        val s = parts.joinToString("\u001F")
        return s.toByteArray()
    }

    private fun deserializeRow(bytes: ByteArray): List<Any?> {
        val s = String(bytes)
        if (s.isEmpty()) return emptyList()
        return s.split("\u001F").map { token ->
            when {
                token == "N" -> null
                token.startsWith("I") -> token.substring(1).toIntOrNull()
                token.startsWith("L") -> token.substring(1).toLongOrNull()
                token.startsWith("D") -> token.substring(1).toDoubleOrNull()
                token.startsWith("F") -> token.substring(1).toFloatOrNull()
                token.startsWith("S") -> {
                    val rest = token.substring(1)
                    val idx = rest.indexOf(':')
                    if (idx >= 0) rest.substring(idx + 1) else rest
                }
                else -> token
            }
        }
    }

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        val count = runBlocking { db.get(countKey(tableName)) }?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0
        val rows = (0 until count).mapNotNull { i ->
            runBlocking { db.get(rowKey(tableName, i)) }?.let { deserializeRow(it) }
        }
        val schema = execCtx.schemaManager.getTable(tableName) ?: TableSchema(tableName, emptyList())
        var idx = -1
        return object : Cursor {
            override fun next(): Boolean {
                if (idx + 1 < rows.size) { idx++; return true }
                return false
            }

            override val row: RowAccessor
                get() = object : RowAccessor {
                    override fun get(index: Int): Any? = rows.getOrNull(idx)?.getOrNull(index)
                    override fun get(name: String): Any? {
                        val colIndex = schema.columns.indexOfFirst { it.name == name }
                        return if (colIndex >= 0) get(colIndex) else null
                    }
                }

            override fun close() {}
        }
    }

    fun seedRows(tableName: String, rows: List<List<Any?>>) {
        runBlocking {
            rows.forEachIndexed { i, r ->
                db.put(rowKey(tableName, i), serializeRow(r))
            }
            db.put(countKey(tableName), rows.size.toString().toByteArray(Charsets.UTF_8))
        }
    }
}
