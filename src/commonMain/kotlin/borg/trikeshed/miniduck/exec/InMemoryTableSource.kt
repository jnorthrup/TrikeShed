package borg.trikeshed.miniduck.exec

/**
 * Minimal in-memory TableSource stub used for tests and JS platform.
 *
 * Methods throw NotImplementedError by default; tests can provide fixtures if needed.
 */
class InMemoryTableSource : TableSource {
    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        throw NotImplementedError("InMemoryTableSource.open is a test stub")
    }

    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor {
        throw NotImplementedError("InMemoryTableSource.openSuspend is a test stub")
    }

    override fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>) {
        throw NotImplementedError("InMemoryTableSource.insert is a test stub")
    }

    override suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>) {
        throw NotImplementedError("InMemoryTableSource.insertSuspend is a test stub")
    }

    // helper used in some tests; accept any schema type to avoid tight coupling
    fun addTable(schema: Any?, rows: List<List<Any?>>) {
        // no-op stub for tests that only need the API to exist
    }
}
