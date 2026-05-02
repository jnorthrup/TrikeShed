package borg.trikeshed.couch.btrfs

import borg.trikeshed.miniduck.exec.TableSource
import borg.trikeshed.miniduck.exec.ExecutionContext

/** Stub - actual implementation incomplete */
class BtrfsTableSource(private val element: BtrfsSandboxElement) : TableSource {
    override suspend fun scan(execCtx: ExecutionContext, table: String): Any = TODO()
    override suspend fun insertSuspend(execCtx: ExecutionContext, table: String, row: List<Any?>) = TODO()
    override suspend fun insert(execCtx: ExecutionContext, table: String, row: List<Any?>) = TODO()
}