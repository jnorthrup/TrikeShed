package borg.trikeshed.job

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class JobSnapshotReceiptTest {
    @Test fun acceptedCommandReceiptSurvivesLaterCommandsAndReplay() = runTest {
        val cas = CasStore.inMemory()
        val log = JobLog.inMemory()
        val id = JobId.of("command-receipt")
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        val submitCid = try {
            nexus.submit(JobCommand.Submit(id, "submit"))
            assertIs<JobEvent.Accepted>(nexus.committed.receive())
            val cid = assertNotNull(nexus.receipt("submit"))
            nexus.submit(JobCommand.Complete(id, "complete", 1))
            assertIs<JobEvent.Accepted>(nexus.committed.receive())
            assertEquals(cid, nexus.receipt("submit"))
            assertNotEquals(cid, nexus.receipt("complete"))
            cid
        } finally { nexus.cancel() }
        val reopened = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        try {
            assertEquals(submitCid, reopened.receipt("submit"))
            assertEquals(reopened.snapshotCid(id.value), reopened.receipt("complete"))
            assertNull(reopened.receipt("never-accepted"))
            assertEquals(1L, CanonicalCbor.decodeJobSnapshot(assertNotNull(cas.get(submitCid))).revision)
        } finally { reopened.cancel() }
    }

    @Test fun snapshotSyncFailureCannotPublishAccepted() = runTest {
        var syncs = 0
        val cas = object : CasStore() {
            override fun sync() { if (++syncs == 2) error("snapshot sync failed") }
        }
        val nexus = JobSupervisorElement.open(this, casStore = cas)
        try {
            nexus.submit(JobCommand.Submit(JobId.of("sync"), "submit"))
            val result = withTimeout(1_000) { nexus.committed.receiveCatching() }
            assertTrue(result.isClosed)
            assertEquals("snapshot sync failed", result.exceptionOrNull()?.message)
            assertNull(nexus.receipt("submit"))
        } finally { nexus.cancel() }
    }

    @Test fun rejectionDoesNotReuseTheNextCommandsLogSequence() = runTest {
        val log = JobLog.inMemory()
        val nexus = JobSupervisorElement.open(this, capacity = 4, jobLog = log)
        try {
            val id = JobId.of("sequence")
            nexus.submit(JobCommand.Submit(id, "submit"))
            nexus.submit(JobCommand.Start(id, "stale", 99))
            nexus.submit(JobCommand.Complete(id, "complete", 1))
            nexus.drain()
            val events = mutableListOf<JobEvent>()
            for (event in nexus.committed) events.add(event)
            assertEquals(2, events.count { it is JobEvent.Accepted })
            assertEquals(1, events.count { it is JobEvent.Rejected })
            assertEquals(listOf(1L, 2L, 3L), log.replay().map { it.sequence }.toList())
            assertEquals("closed", nexus.snapshot("sequence")?.lifecycle)
            assertEquals(2L, nexus.snapshot("sequence")?.revision)
        } finally {
            nexus.cancel()
        }
    }

    @Test fun acceptedReceiptNamesRetainedBytesBeforeTheConsumerResumes() = runTest {
        val cas = CasStore.inMemory()
        val nexus = JobSupervisorElement.open(this, casStore = cas)
        try {
            val observed = async(Dispatchers.Unconfined) {
                assertIs<JobEvent.Accepted>(nexus.committed.receive())
                val snapshot = assertNotNull(nexus.snapshot("receipt"), "Accepted must publish readable state")
                val cid = assertNotNull(nexus.snapshotCid("receipt"))
                val bytes = assertNotNull(cas.get(cid), "A snapshot receipt must resolve in its CAS")
                assertContentEquals(CanonicalCbor.encode(snapshot), bytes)
                assertEquals(snapshot, CanonicalCbor.decodeJobSnapshot(bytes))
            }
            nexus.submit(JobCommand.Submit(JobId.of("receipt"), "receipt-submit"))
            observed.await()
        } finally {
            nexus.cancel()
        }
    }

    @Test fun suppliedLogRebuildsReceiptsAndContinuesAfterARejectedCommand() = runTest {
        val cas = CasStore.inMemory()
        val log = JobLog.inMemory()
        val id = JobId.of("replay")
        val submit = JobCommand.Submit(id, "submit")
        val stale = JobCommand.Start(id, "stale", 99)
        for ((index, cmd) in listOf(submit, stale).withIndex()) {
            val bytes = CanonicalCbor.encode(cmd)
            cas.put(bytes)
            log.append(index + 1L, bytes)
        }
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        try {
            val snapshot = assertNotNull(nexus.snapshot("replay"))
            assertEquals(1L, snapshot.revision)
            assertContentEquals(CanonicalCbor.encode(snapshot), cas.get(assertNotNull(nexus.snapshotCid("replay"))))
            nexus.submit(JobCommand.Start(id, "start", 1))
            assertIs<JobEvent.Accepted>(withTimeout(1_000) { nexus.committed.receive() })
            assertEquals("active", nexus.snapshot("replay")?.lifecycle)
            assertEquals(listOf(1L, 2L, 3L), log.replay().map { it.sequence }.toList())
        } finally {
            nexus.cancel()
        }
    }

    @Test fun failedSnapshotRetentionCannotPublishOrAdmitFurtherWork() = runTest {
        var writes = 0
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId {
                if (++writes > 1) error("snapshot retention refused")
                return super.put(bytes)
            }
        }
        val nexus = JobSupervisorElement.open(this, casStore = cas)
        try {
            nexus.submit(JobCommand.Submit(JobId.of("failed"), "submit"))
            val failure = withTimeout(1_000) { nexus.committed.receiveCatching() }
            assertTrue(failure.isClosed, "No Accepted event without retained snapshot bytes")
            assertTrue(assertNotNull(failure.exceptionOrNull()).message.orEmpty().contains("snapshot retention refused"))
            assertNull(nexus.snapshot("failed"))
            assertNull(nexus.snapshotCid("failed"))
            assertFalse(nexus.trySubmit(JobCommand.Submit(JobId.of("later"), "later")))
        } finally {
            nexus.cancel()
        }
    }

    @Test fun wrongCasBytesCannotProduceAnAcceptedReceipt() = runTest {
        val cas = object : CasStore() {
            override fun get(cid: ContentId): ByteArray? = byteArrayOf(0)
        }
        val nexus = JobSupervisorElement.open(this, casStore = cas)
        try {
            nexus.submit(JobCommand.Submit(JobId.of("wrong"), "wrong"))
            val failure = withTimeout(1_000) { nexus.committed.receiveCatching() }
            assertTrue(failure.isClosed)
            assertNotNull(failure.exceptionOrNull())
            assertNull(nexus.snapshot("wrong"))
            assertEquals(0, nexus.instrumentation.walAppendAttempts)
        } finally {
            nexus.cancel()
        }
    }
}
