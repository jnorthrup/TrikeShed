package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TribunalCommitIdentityTest {
    /** Existing APIs only: the old same-job waiter returns the buffered unrelated refusal. */
    @Test fun delayedRejectionCannotAcknowledgeAnotherRequestForTheSameJob() = runTest {
        val id = JobId.of("case/identity")
        val log = JobLog.inMemory()
        log.append(1L, CanonicalCbor.encode(JobCommand.Submit(id, "seed")))
        log.append(2L, CanonicalCbor.encode(JobCommand.Start(id, "start", 1L)))
        val cas = CasStore.inMemory()
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        val instance = TribunalInstance(nexus, TribunalInstance.schemaPlan(), listOf(id.value))
        try {
            nexus.submit(JobCommand.Update(id, "unrelated-stale", 0L))
            runCurrent() // The actual unrelated refusal remains queued before the new command.
            val event = assertIs<JobEvent.Accepted>(instance.advance(id.value, "complete", "actual-complete", 2L))
            assertEquals(id, event.jobId)
            assertEquals(3L, event.sequence)
            assertEquals("closed", instance.lifecycle(id.value))
            assertEquals(3L, instance.revision(id.value))
            val receipt = assertNotNull(nexus.receipt("actual-complete"))
            val bytes = assertNotNull(cas.get(receipt))
            assertEquals(receipt, ContentId.of(bytes))
            assertEquals("closed", CanonicalCbor.decodeJobSnapshot(bytes).lifecycle)
            assertNull(nexus.receipt("unrelated-stale"))
        } finally {
            val output = launch { for (event in nexus.committed) { } }
            nexus.drain()
            output.join()
        }
    }
}
