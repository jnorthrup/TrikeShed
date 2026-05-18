package borg.trikeshed.miniduck.exec

import borg.trikeshed.userspace.database.LsmrDatabase

/**
 * JVM actual for LsmrTableSource. Delegates to InMemoryTableSource for now so the
 * miniduck/integration compile path stays working without a storage rewrite.
 */
class LsmrTableSource(private val db: LsmrDatabase, val blockSizeThreshold: Int = 128) : TableSource {
    private val delegate = InMemoryTableSource()

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor = delegate.open(execCtx, tableName)

    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor = delegate.openSuspend(execCtx, tableName)

    override fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>) =
        delegate.insert(execCtx, tableName, row)

    override suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>) =
        delegate.insertSuspend(execCtx, tableName, row)

    override fun seedRows(tableName: String, rows: List<List<Any?>>) {
        delegate.seedRows(tableName, rows)
    }
}
