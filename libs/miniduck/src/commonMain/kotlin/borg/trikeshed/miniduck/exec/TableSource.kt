package borg.trikeshed.miniduck.exec

/** Stub interface for table source operations. */
interface TableSource {
    fun open(execCtx: ExecutionContext, tableName: String): Cursor
    suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor

    fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>)
    suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>)

    // convenience helpers used by some tests
    fun seedRows(tableName: String, rows: List<List<Any?>>) { /* optional */ }

    // legacy alias
    suspend fun scan(execCtx: ExecutionContext, table: String): Cursor = openSuspend(execCtx, table)
}

/** Stub interface for a positioned row accessor. */
interface RowAccessor {
    operator fun get(index: Int): Any?
    operator fun get(name: String): Any?
    val size: Int get() = 0
    fun columnName(index: Int): String? = null
}
