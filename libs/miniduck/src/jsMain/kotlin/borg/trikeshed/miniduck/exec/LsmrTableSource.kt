package borg.trikeshed.miniduck.exec

import borg.trikeshed.userspace.database.LsmrDatabase
import borg.trikeshed.cursor.Cursor

/**
 * JS platform actual for LsmrTableSource. Delegate to InMemoryTableSource for tests/runtime
 * so common expect APIs compile and JS tests can run without a full LSMR implementation.
 */
class LsmrTableSource(private val db: LsmrDatabase,val blockSizeThreshold: Int = 128) : TableSource {

   val delegate = InMemoryTableSource()

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor = delegate.open(execCtx, tableName)

    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor = delegate.openSuspend(execCtx, tableName)

    override fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>) = delegate.insert(execCtx, tableName, row)

    override suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>) = delegate.insertSuspend(execCtx, tableName, row)
}
