package borg.trikeshed.miniduck.exec

import borg.trikeshed.cursor.*

/** Stub interface for table source operations */
interface TableSource {
    suspend fun scan(execCtx: ExecutionContext, table: String): Cursor
    suspend fun insertSuspend(execCtx: ExecutionContext, table: String, row: List<Any?>)
    suspend fun insert(execCtx: ExecutionContext, table: String, row: List<Any?>)
}

/** Stub interface for row accessor */
interface RowAccessor {
    operator fun get(row: Int, col: String): Any?
    operator fun get(row: Int, col: Int): Any?
}