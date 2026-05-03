package borg.trikeshed.couch.btrfs

import borg.trikeshed.miniduck.exec.Cursor
import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.exec.InMemoryTableSource
import borg.trikeshed.miniduck.exec.TableSource

class BtrfsTableSource(private val element: BtrfsSandboxElement) : TableSource {
    private val delegate = InMemoryTableSource()

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor = delegate.open(execCtx, tableName)
    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor = delegate.openSuspend(execCtx, tableName)

    override suspend fun insertSuspend(execCtx: ExecutionContext, table: String, row: List<Any?>) =
        delegate.insertSuspend(execCtx, table, row)

    override fun insert(execCtx: ExecutionContext, table: String, row: List<Any?>) =
        delegate.insert(execCtx, table, row)

    override fun seedRows(tableName: String, rows: List<List<Any?>>) {
        delegate.seedRows(tableName, rows)
    }
}
