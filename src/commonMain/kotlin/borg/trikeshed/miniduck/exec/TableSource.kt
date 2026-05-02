package borg.trikeshed.miniduck.exec

import borg.trikeshed.cursor.Cursor

/**
 * Minimal TableSource interface used by platform implementations.
 * Keep signatures narrow and stable so platform actuals can implement them.
 */
interface TableSource {
    fun open(execCtx: ExecutionContext, tableName: String): Cursor
    suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor
    fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>)
    suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>)
}
