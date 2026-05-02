package borg.trikeshed.couch.btrfs

import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.exec.TableSource

/** Stub - actual implementation incomplete */
class BtrfsTableSource(private val element: BtrfsSandboxElement) : TableSource {
    override fun open(execCtx: ExecutionContext, tableName: String): borg.trikeshed.cursor.Cursor = borg.trikeshed.lib.Join.emptySeriesOf()
    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): borg.trikeshed.cursor.Cursor = borg.trikeshed.lib.Join.emptySeriesOf()

    override suspend fun scan(execCtx: ExecutionContext, table: String)  = TODO()
    override suspend fun insertSuspend(execCtx: ExecutionContext, table: String, row: List<Any?>) = TODO()
    override fun insert(execCtx: ExecutionContext, table: String, row: List<Any?>) = TODO()
}
