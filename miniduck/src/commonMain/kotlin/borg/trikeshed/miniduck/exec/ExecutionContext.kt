package borg.trikeshed.miniduck.exec

import borg.trikeshed.miniduck.schema.SchemaManager
import borg.trikeshed.miniduck.sql.PlannerConfig
import borg.trikeshed.miniduck.runBlockingCommon

/**
 * Execution context and cursor/row accessor abstractions.
 * Placed under package "...exec" so other code can import borg.trikeshed.miniduck.exec.*
 */

// A source of table data used at execution time.
interface TableSource {
    // Existing synchronous API (abstract): implementers provide this.
    fun open(execCtx: ExecutionContext, tableName: String): Cursor

    /** Optional insert API; default not supported. Implementers may override to support writes. */
    fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>) {
        throw UnsupportedOperationException("insert not supported")
    }

    // Suspend-aware defaults that bridge to the synchronous API for backward compatibility.
    suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor = runBlockingCommon { open(execCtx, tableName) }
    suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>) = runBlockingCommon { insert(execCtx, tableName, row) }
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
