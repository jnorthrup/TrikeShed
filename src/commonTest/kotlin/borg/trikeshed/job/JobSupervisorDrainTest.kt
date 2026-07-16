package borg.trikeshed.job

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobSupervisorDrainTest {

    @Test
    fun drainWaitsForAcceptedCommandsBeforeClosing() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 2)

        nexus.submit(JobCommand.Submit(JobId.of("j-1"), "k-1"))
        nexus.submit(JobCommand.Submit(JobId.of("j-2"), "k-2"))

        nexus.drain()

        assertEquals(2L, nexus.committedSequence, "drain returned before accepted commands committed")
        assertEquals(ElementState.CLOSED, nexus.state)
    }

    @Test
    fun casFailureStopsBeforeWalReducerAndVisibility() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 1)
        nexus.injectCasFailure()

        nexus.submit(JobCommand.Submit(JobId.of("j-cas-fail"), "k-cas-fail"))
        nexus.drain()

        assertEquals(1, nexus.instrumentation.casWriteAttempts)
        assertEquals(0, nexus.instrumentation.casWriteCount)
        assertEquals(0, nexus.instrumentation.walAppendAttempts)
        assertEquals(0, nexus.instrumentation.reducerApplyCount)
        assertEquals(0L, nexus.committedSequence)
        assertEquals(null, nexus.snapshot("j-cas-fail"))
    }

    @Test
    fun openRejectsParentScopeWithoutJob() {
        val parentlessScope = object : CoroutineScope {
            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        }
        val result = runCatching {
            JobSupervisorElement.open(parentlessScope)
        }
        result.getOrNull()?.cancel()

        assertTrue(
            result.exceptionOrNull() is IllegalArgumentException,
            "a parentless scope would detach the nexus supervisor",
        )
    }

    @Test
    fun openRejectsNonPositiveCommandCapacity() = runTest {
        val zero = runCatching { JobSupervisorElement.open(this, capacity = 0) }
        zero.getOrNull()?.cancel()
        assertTrue(zero.exceptionOrNull() is IllegalArgumentException)

        val negative = runCatching { JobSupervisorElement.open(this, capacity = -1) }
        negative.getOrNull()?.cancel()
        assertTrue(negative.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun cancelClosesOwnedCommandAndCommittedChannels() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 1)

        nexus.cancel()

        assertEquals(ElementState.CLOSED, nexus.state)
        assertTrue(nexus.commandChannelClosed)
        assertTrue(nexus.committedChannelClosed)
    }

    @Test
    fun submitRawAcceptsValidSubmitJson() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 4)
        val json = """{"operation":"submit","jobId":"j-raw-1","idempotencyKey":"ik-raw-1"}""".encodeToByteArray()

        nexus.submitRaw(json)
        // drain closes channels so no further submits
        nexus.drain()

        assertEquals(1L, nexus.committedSequence)
        assertEquals("submitted", nexus.snapshot("j-raw-1")?.lifecycle)
    }

    @Test
    fun submitRawAcceptsBlockWithReason() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 4)
        nexus.submit(JobCommand.Submit(JobId.of("j-block-1"), "k-b1"))
        val json = """{"operation":"block","jobId":"j-block-1","idempotencyKey":"ik-b2","expectedRevision":"1"}""".encodeToByteArray()
        nexus.submitRaw(json)
        nexus.drain()

        assertEquals(2L, nexus.committedSequence)
        assertEquals("blocked", nexus.snapshot("j-block-1")?.lifecycle)
    }

    @Test
    fun submitRawRejectsUnknownOperation() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 4)
        val json = """{"operation":"hack-the-planet","jobId":"j-bad","idempotencyKey":"ik-bad"}""".encodeToByteArray()

        nexus.submitRaw(json)
        nexus.drain()

        assertEquals(0L, nexus.committedSequence, "unknown op must not be committed")
        assertEquals(null, nexus.snapshot("j-bad"))
    }

    @Test
    fun replayResumesFromLastDurableSequenceIncludingRejections() = runTest {
        val wal = mutableMapOf(
            "4" to CanonicalCbor.encode(
                JobCommand.Submit(JobId.of("j-replay"), "k-submit"),
            ),
            "9" to CanonicalCbor.encode(
                JobCommand.Start(JobId.of("j-replay"), "k-stale", expectedRevision = 99),
            ),
        )

        val nexus = JobSupervisorElement.open(this, capacity = 1, walData = wal)

        assertEquals(9L, nexus.committedSequence)
        assertEquals(1L, nexus.snapshot("j-replay")?.revision)
        nexus.drain()
    }

    @Test
    fun walFailureStopsAfterCasBeforeReducerAndVisibility() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 1)
        nexus.injectWalFailure()

        nexus.submit(JobCommand.Submit(JobId.of("j-wal-fail"), "k-wal-fail"))
        nexus.drain()

        assertEquals(1, nexus.instrumentation.casWriteCount, "CAS must succeed before WAL")
        assertEquals(1, nexus.instrumentation.walAppendAttempts)
        assertEquals(0, nexus.instrumentation.walAppendCount, "WAL append must fail")
        assertEquals(0, nexus.instrumentation.reducerApplyCount, "reducer must not run after WAL failure")
        assertEquals(0L, nexus.committedSequence, "committedSequence must not advance after WAL failure")
        assertEquals(null, nexus.snapshot("j-wal-fail"), "snapshot must not be visible after WAL failure")
    }

    @Test
    fun drainRejectsCommandsSubmittedAfterDrainingBegins() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 1)

        // Transition to DRAINING without joining the reactor.
        nexus.beginDrain()

        assertEquals(ElementState.DRAINING, nexus.state)
        assertFalse(nexus.trySubmit(JobCommand.Submit(JobId.of("j-late"), "k-late")),
            "trySubmit must reject after draining begins")

        nexus.cancel()
    }

    @Test
    fun duplicateIdempotencyKeyDoesNotAdvanceSequenceOrSnapshot() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 4)

        nexus.submit(JobCommand.Submit(JobId.of("j-dup"), "k-dup"))
        nexus.submit(JobCommand.Submit(JobId.of("j-dup"), "k-dup"))
        nexus.drain()

        assertEquals(1L, nexus.committedSequence,
            "duplicate idempotency key must not advance committed sequence a second time")
        val snap = nexus.snapshot("j-dup")
        assertTrue(snap != null, "first submit must produce a snapshot")
        assertEquals(1L, snap!!.revision, "revision must remain 1 — no second transition")
    }

    @Test
    fun staleExpectedRevisionIsRejectedWithoutAdvancingCommittedSequence() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 4)

        nexus.submit(JobCommand.Submit(JobId.of("j-stale"), "k-submit"))
        nexus.submit(JobCommand.Start(JobId.of("j-stale"), "k-start", expectedRevision = 99))
        nexus.drain()

        assertEquals(1L, nexus.committedSequence,
            "only the accepted submit should advance committed sequence")
        val snap = nexus.snapshot("j-stale")
        assertTrue(snap != null)
        assertEquals(1L, snap!!.revision,
            "stale-revision start must not advance the snapshot")
    }

    @Test
    fun acceptedSubmitEmitsCommittedEventFactAndSnapshot() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 4)

        nexus.submit(JobCommand.Submit(JobId.of("j-evt"), "k-evt"))
        nexus.drain()

        assertEquals(1L, nexus.committedSequence)
        val snap = nexus.snapshot("j-evt")
        assertTrue(snap != null, "accepted submit must produce a snapshot")
        val facts = nexus.facts("j-evt")
        assertEquals(1, facts.size, "accepted submit must produce exactly one fact")
        assertTrue(facts[0].isNotEmpty(),
            "fact must carry a content-addressed canonical bytes record")
    }

    @Test
    fun jobWithCompletedDependencyBecomesReadyNotSubmitted() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 8)
        val parent = JobId.of("j-parent")
        val child = JobId.of("j-child")

        nexus.submit(JobCommand.Submit(parent, "k-parent"))
        nexus.submit(JobCommand.Complete(parent, "k-complete", expectedRevision = 1))
        nexus.submit(JobCommand.Submit(child, "k-child", dependencies = listOf(parent)))
        nexus.drain()

        assertEquals(3L, nexus.committedSequence)
        val parentSnap = nexus.snapshot("j-parent")
        assertEquals("closed", parentSnap?.lifecycle)
        val childSnap = nexus.snapshot("j-child")
        assertEquals("ready", childSnap?.lifecycle,
            "child with all-closed dependencies must derive lifecycle 'ready', not 'submitted'")
    }

    @Test
    fun jobWithUnsatisfiedDependencyStaysSubmitted() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 4)
        val parent = JobId.of("j-parent-open")
        val child = JobId.of("j-child-blocked")

        nexus.submit(JobCommand.Submit(parent, "k-parent"))
        nexus.submit(JobCommand.Submit(child, "k-child", dependencies = listOf(parent)))
        nexus.drain()

        assertEquals("submitted", nexus.snapshot("j-parent-open")?.lifecycle)
        assertEquals("submitted", nexus.snapshot("j-child-blocked")?.lifecycle,
            "child with a non-closed dependency must stay 'submitted', not 'ready'")
    }

    @Test
    fun cancellingParentScopeCancelsSupervisorRoot() {
        val parentJob = kotlinx.coroutines.CompletableDeferred<Unit>()
        val parentScope = kotlinx.coroutines.CoroutineScope(parentJob)
        val nexus = JobSupervisorElement.open(parentScope, capacity = 2)
        assertTrue(nexus.isActive)

        parentJob.cancel()

        assertTrue(nexus.rootJob.isCancelled,
            "C06: cancelling the parent scope must cancel the supervisor's root SupervisorJob")
    }

    @Test
    fun cancelClosesAllOwnedStateChannelsNotJustCommandAndCommitted() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 2)

        nexus.cancel()

        assertEquals(ElementState.CLOSED, nexus.state)
        assertTrue(nexus.commandChannelClosed, "command channel must be closed")
        assertTrue(nexus.committedChannelClosed, "committed channel must be closed")
        assertTrue(nexus.factsChannelClosed, "C07: facts channel must be closed")
        assertTrue(nexus.activationsChannelClosed, "activations channel must be closed")
    }

    @Test
    fun failOnOneJobDoesNotCorruptSiblingSnapshot() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 8)
        val survivor = JobId.of("j-survivor")
        val victim = JobId.of("j-victim")

        nexus.submit(JobCommand.Submit(survivor, "k-survivor"))
        nexus.submit(JobCommand.Submit(victim, "k-victim"))
        nexus.submit(JobCommand.Fail(victim, "k-fail", expectedRevision = 1, reason = "crash"))
        nexus.drain()

        assertEquals(3L, nexus.committedSequence)
        val survivorSnap = nexus.snapshot("j-survivor")
        val victimSnap = nexus.snapshot("j-victim")
        assertEquals("submitted", survivorSnap?.lifecycle,
            "C06: sibling job must remain intact when another job fails")
        assertEquals("failed", victimSnap?.lifecycle)
    }

    @Test
    fun acceptedSnapshotHasContentAddressedCid() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 2)

        nexus.submit(JobCommand.Submit(JobId.of("j-cid"), "k-cid"))
        nexus.drain()

        val cid = nexus.snapshotCid("j-cid")
        assertTrue(cid != null, "accepted snapshot must have a CID")
        assertTrue(cid!!.value.startsWith("sha256:"))
        assertEquals(71, cid.value.length, "snapshot CID must be sha256: + 64 hex chars")
    }

    @Test
    fun retryIncrementsAttemptCountAndPreservesHistory() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 8)
        val job = JobId.of("j-retry")

        nexus.submit(JobCommand.Submit(job, "k-submit"))
        nexus.submit(JobCommand.Fail(job, "k-fail", expectedRevision = 1, reason = "boom"))
        nexus.submit(JobCommand.Retry(job, "k-retry", expectedRevision = 2))
        nexus.drain()

        val snap = nexus.snapshot("j-retry")
        assertTrue(snap != null)
        assertEquals(2, snap!!.attemptCount,
            "I2: retry must increment attemptCount while preserving prior attempt history")
        assertEquals("submitted", snap.lifecycle,
            "retry resets lifecycle to 'submitted'")
    }
}
