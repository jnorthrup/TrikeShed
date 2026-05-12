package borg.trikeshed.couch.btrfs

import borg.trikeshed.couch.wal.WalEntry
import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.schema.InMemorySchemaManager
import borg.trikeshed.miniduck.sql.PlannerConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class BtrfsHarness {
    suspend fun runDemo(sandbox: BtrfsSandboxElement): CharSequence {
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val wal = BtrfsWal(sandbox)
        wal.open()

        val tableSource = BtrfsTableSource(sandbox)

        // Structured concurrency orchestration
        kotlinx.coroutines.coroutineScope {
            launch {
                wal.append(WalEntry(payload = "couch_transaction_1"))
                wal.append(WalEntry(payload = "couch_transaction_2"))
            }

            launch {
                val execCtx = ExecutionContext(schemaManager = InMemorySchemaManager(), config = PlannerConfig(), tableSource = tableSource)
                kotlinx.coroutines.delay(100)
                tableSource.insertSuspend(execCtx, "events_table", listOf(1, "duck_event_1"))
                tableSource.insertSuspend(execCtx, "events_table", listOf(2, "duck_event_2"))
            }
        }

        val walSeries = wal.readFrom(1L)
        val walPayloads = (0 until walSeries.a).map { walSeries.b(it).payload }

        wal.close()
        sandbox.close()

        return "Harness run complete: WAL stored ${walSeries.a} entries, including: $walPayloads. Sandbox tree size is ${sandbox.btree.size()}."
    }
}
