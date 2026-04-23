package borg.trikeshed.couch.miniduck.exec

import borg.trikeshed.couch.miniduck.schema.SchemaManager
import borg.trikeshed.couch.miniduck.sql.PlannerConfig

/**
 * Execution context and cursor/row accessor abstractions.
 * Placed under package "...exec" so other code can import borg.trikeshed.couch.miniduck.exec.*
 */

// A source of table data used at execution time.
interface TableSource {
    fun open(execCtx: ExecutionContext, tableName: String): Cursor
}

data class ExecutionContext(
    val schemaManager: SchemaManager,
    val config: PlannerConfig,
    val tableSource: TableSource
)

interface Cursor {
    fun next(): Boolean
    val row: RowAccessor
    fun close()
}

interface RowAccessor {
    fun get(index: Int): Any?
    fun get(name: String): Any?
}
