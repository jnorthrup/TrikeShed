package borg.trikeshed.miniduck.exec

/** Stub interface for table source operations. */
interface TableSource {
    fun open(execCtx: ExecutionContext, tableName: CharSequence): Cursor
    suspend fun openSuspend(execCtx: ExecutionContext, tableName: CharSequence): Cursor

    fun insert(execCtx: ExecutionContext, tableName: CharSequence, row: List<Any?>)
    suspend fun insertSuspend(execCtx: ExecutionContext, tableName: CharSequence, row: List<Any?>)

    // convenience helpers used by some tests
    fun seedRows(tableName: CharSequence, rows: List<List<Any?>>) { /* optional */ }

    // legacy alias
    suspend fun scan(execCtx: ExecutionContext, table: CharSequence): Cursor = openSuspend(execCtx, table)
}

/** Stub interface for a positioned row accessor. */
interface RowAccessor {
    operator fun get(index: Int): Any?
    operator fun get(name: CharSequence): Any?
    val size: Int get() = 0
    fun columnName(index: Int): CharSequence? = null
}
