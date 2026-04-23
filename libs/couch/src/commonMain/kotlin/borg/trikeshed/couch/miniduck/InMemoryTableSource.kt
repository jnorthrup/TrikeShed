package borg.trikeshed.couch.miniduck.exec

import borg.trikeshed.couch.miniduck.schema.TableSchema

/**
 * Simple in-memory table source useful for tests and examples.
 */
class InMemoryTableSource : TableSource {
    private val tables = mutableMapOf<String, MutableList<List<Any?>>>()
    private val schemas = mutableMapOf<String, TableSchema>()

    fun addTable(schema: TableSchema, rows: List<List<Any?>>) {
        schemas[schema.name] = schema
        tables[schema.name] = rows.toMutableList()
    }

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        val rows = tables[tableName] ?: emptyList<List<Any?>>()
        val schema = execCtx.schemaManager.getTable(tableName) ?: schemas[tableName]

        return object : Cursor {
            var idx = -1

            override fun next(): Boolean {
                if (idx + 1 < rows.size) {
                    idx++
                    return true
                }
                return false
            }

            override val row: borg.trikeshed.couch.miniduck.exec.RowAccessor
                get() = object : borg.trikeshed.couch.miniduck.exec.RowAccessor {
                    override fun get(index: Int): Any? = rows.getOrNull(idx)?.getOrNull(index)

                    override fun get(name: String): Any? {
                        val colIndex = schema?.columns?.indexOfFirst { it.name == name } ?: -1
                        return if (colIndex >= 0) get(colIndex) else null
                    }
                }

            override fun close() {}
        }
    }
}