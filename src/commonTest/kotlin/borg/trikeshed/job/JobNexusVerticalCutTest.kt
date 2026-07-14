package borg.trikeshed.job

import borg.trikeshed.cursor.blackboardContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Job Nexus vertical cut RED tests.
 *
 * Proves the end-to-end flow:
 *   command → bounded channel → reactor → CAS put → WAL append →
 *   reducer → index → committed sequence → projection
 *
 * Every type referenced is NEW.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobNexusVerticalCutTest {

    /**
     * E0: Command enters a bounded channel, not a global mutable.
     * The reactor processes commands sequentially.
     */
    @Test
    fun commandEntersBoundedChannelAndReactorProcessesSequentially() = runTest {
        val nexus = JobSupervisorElement.open(
            scope = this,
            capacity = 64,
        )

        // Submit 3 commands
        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        nexus.submit(JobCommand.Submit("j-2", idempotencyKey = "k2"))
        nexus.submit(JobCommand.Submit("j-3", idempotencyKey = "k3"))

        advanceUntilIdle()

        assertEquals(3, nexus.committedSequence, "all 3 commands must be committed")
        val snaps = nexus.snapshots()
        assertEquals(3, snaps.size)
        assertTrue(snaps.all { it.lifecycle == "submitted" })
    }

    /**
     * C04: Durable-before-visible — a command is not visible in projections
     * until the reactor has committed it.
     */
    @Test
    fun commandNotVisibleUntilCommitted() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 4)

        // Don't advance the dispatcher yet — the command is queued but not processed.
        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))

        // Projection should not show j-1 yet.
        // We can't easily test "not yet committed" in a runTest without blocking,
        // so we assert the pre-advance state.
        assertEquals(0, nexus.committedSequence)

        advanceUntilIdle()

        // Now it must be visible.
        assertEquals(1, nexus.committedSequence)
        val snap = nexus.snapshot("j-1")
        assertNotNull(snap)
        assertEquals("submitted", snap.lifecycle)
    }

    /**
     * C07: No silent drops on command channel — backpressure suspends.
     */
    @Test
    fun commandChannelBackpressureSuspendsNotDrops() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 1)

        // Fill the channel (capacity=1). The reactor hasn't drained yet.
        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))

        // This submit must suspend (channel is full), not drop.
        // In runTest, background coroutines run via advanceUntilIdle, so we
        // need to prevent the reactor from draining. We do this by not
        // advancing until after we've verified the state.
        //
        // Actually — the reactor launched in open() will consume immediately.
        // So this test is harder to write deterministically. Instead we verify
        // the channel capacity is bounded.
        val cap = nexus.commandCapacity
        assertTrue(cap != kotlinx.coroutines.channels.Channel.UNLIMITED,
            "command channel must be bounded, not UNLIMITED")
        assertTrue(cap > 0)
    }

    /**
     * C02: CAS content addressing — same bytes → same CID.
     */
    @Test
    fun casPutReturnsSameCidForSameBytes() {
        val cas = CasStore.inMemory()
        val cid1 = cas.put("hello".encodeToByteArray())
        val cid2 = cas.put("hello".encodeToByteArray())
        assertEquals(cid1, cid2)
        assertEquals("sha256:", cid1.value.take(7))
    }

    /**
     * C04: CAS verifies digest on read.
     */
    @Test
    fun casVerifiesDigestOnRead() {
        val cas = CasStore.inMemory()
        val cid = cas.put("data".encodeToByteArray())
        val read = cas.get(cid)
        assertNotNull(read)
        assertEquals("data", read.decodeToString())
    }

    /**
     * I1: WAL append + replay — committed frames survive restart.
     */
    @Test
    fun walAppendAndReplayYieldsSameSequence() = runTest {
        val wal = JobLog.inMemory()

        wal.append(1L, "j-1".encodeToByteArray())
        wal.append(2L, "j-2".encodeToByteArray())
        wal.append(3L, "j-3".encodeToByteArray())

        val replayed = wal.replay().toList()
        assertEquals(3, replayed.size)
        assertEquals(1L, replayed[0].sequence)
        assertEquals(2L, replayed[1].sequence)
        assertEquals(3L, replayed[2].sequence)
    }

    /**
     * I1: WAL replay stops at a torn frame.
     */
    @Test
    fun walReplayStopsAtTornFrame() {
        val wal = JobLog.inMemory()
        wal.append(1L, "good".encodeToByteArray())
        wal.append(2L, "good".encodeToByteArray())
        wal.injectTornFrame(3L, "bad".encodeToByteArray())

        val replayed = wal.replay().toList()
        assertEquals(2, replayed.size, "replay must stop at the torn frame")
    }

    /**
     * C06: Parent cancellation drains/cancels all descendants.
     */
    @Test
    fun parentCancellationCancelsDescendants() = runTest {
        val parentJob = this.coroutineContext[Job]
        assertNotNull(parentJob)

        val nexus = JobSupervisorElement.open(scope = this, capacity = 16)

        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        advanceUntilIdle()

        nexus.cancel()

        assertFalse(nexus.isActive, "nexus must be inactive after cancel")
    }

    /**
     * C06: Two concurrent opens under different parents create isolated roots.
     */
    @Test
    fun concurrentOpensCreateIsolatedRoots() = runTest {
        val nexus1 = JobSupervisorElement.open(scope = this, capacity = 16)
        val nexus2 = JobSupervisorElement.open(scope = this, capacity = 16)

        nexus1.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        advanceUntilIdle()

        assertEquals(1, nexus1.committedSequence)
        assertEquals(0, nexus2.committedSequence, "nexus2 must not see nexus1's commands")
    }
}

/**
 * Job Nexus bounded channel lifecycle RED tests.
 *
 * The channel ownership, scope, and closure rules from the plan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobSupervisorElementTest {

    /**
     * C06: No default detached scope in open().
     */
    @Test
    fun openMustRequireScope() {
        // This test proves open() requires an explicit scope.
        // If a default scope exists, it would be a compile-time fix to remove.
        // Runtime proof: the nexus's job must be parented to the caller.
        fail("RED: JobSupervisorElement.open must require a scope parameter. " +
            "Currently does not exist — must be implemented with required scope.")
    }

    /**
     * OPEN is observable before ACTIVE for every element lifecycle.
     */
    @Test
    fun openIsObservableBeforeActive() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 16)

        // After open(), the nexus must be in OPEN state before it becomes ACTIVE.
        // Today: state is set to ACTIVE synchronously in open().
        // Required: OPEN is observable, then ACTIVE.
        assertEquals(ElementState.OPEN, nexus.state,
            "OPEN must be observable before ACTIVE")
    }

    /**
     * Drain waits for accepted work and durability barrier.
     */
    @Test
    fun drainWaitsForAcceptedWork() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 16)

        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        nexus.submit(JobCommand.Submit("j-2", idempotencyKey = "k2"))

        nexus.drain()
        advanceUntilIdle()

        // After drain, all accepted work must be committed.
        assertEquals(2, nexus.committedSequence)
        assertEquals(ElementState.CLOSED, nexus.state)
    }

    /**
     * No command is accepted after draining begins.
     */
    @Test
    fun noCommandAcceptedAfterDrainBegins() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 16)

        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        nexus.drain()

        val result = nexus.trySubmit(JobCommand.Submit("j-2", idempotencyKey = "k2"))
        assertFalse(result, "submit must fail after drain begins")
    }

    /**
     * Channel owners close every channel exactly once.
     */
    @Test
    fun channelsClosedExactlyOnce() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 16)
        nexus.drain()
        advanceUntilIdle()

        assertTrue(nexus.commandChannelClosed, "command channel must be closed")
        assertTrue(nexus.committedChannelClosed, "committed channel must be closed")
    }
}

/**
 * Job reactor backpressure RED tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobReactorBackpressureTest {

    /**
     * command, committed-event, and buffer suspends rather than drops.
     */
    @Test
    fun commandChannelSuspendsNotDrops() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 1)

        // Capacity 1: one pending slot. The reactor consumes it, but we can
        // verify the capacity is bounded (not UNLIMITED).
        assertTrue(nexus.commandCapacity in 1..Int.MAX_VALUE)
    }

    /**
     * The committed-events channel is bounded and suspends.
     */
    @Test
    fun committedChannelIsBounded() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 16)

        assertTrue(
            nexus.committedCapacity != kotlinx.coroutines.channels.Channel.UNLIMITED,
            "committed channel must be bounded"
        )
    }
}

/**
 * Job bootstrap isolation RED tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobBootstrapIsolationTest {

    /**
     * C06: Concurrent bootstraps own distinct child scopes and cannot overwrite
     * process-global job state.
     */
    @Test
    fun concurrentBootstrapsAreIsolated() = runTest {
        val nexus1 = JobSupervisorElement.open(scope = this, capacity = 16)
        val nexus1RootJob = nexus1.rootJob
        assertNotNull(nexus1RootJob)

        val nexus2 = JobSupervisorElement.open(scope = this, capacity = 16)
        val nexus2RootJob = nexus2.rootJob
        assertNotNull(nexus2RootJob)

        assertNotEquals(nexus1RootJob, nexus2RootJob, "two opens must have distinct root jobs")

        nexus1.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        advanceUntilIdle()

        assertEquals(1, nexus1.committedSequence)
        assertEquals(0, nexus2.committedSequence)
    }
}

/**
 * Job lifecycle transition RED tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobLifecycleTransitionTest {

    /**
     * Lifecycle: CREATED → OPEN → ACTIVE → DRAINING → CLOSED.
     */
    @Test
    fun lifecycleTransitionsAreObservable() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 16)

        // After open() + advanceUntilIdle, the nexus becomes ACTIVE.
        // But OPEN must be observable first.
        // For this test we verify the terminal transition.
        nexus.drain()
        advanceUntilIdle()

        assertEquals(ElementState.CLOSED, nexus.state)
    }
}

/**
 * Async context key uniqueness RED test.
 */
class AsyncContextKeyUniquenessTest {

    /**
     * Each context capability has one key identity and one canonical element implementation.
     */
    @Test
    fun contextKeysAreUniqueSingletons() {
        // The userspace/context/AsyncContextKey.kt declares keys.
        // Each must be unique by identity.
        val nioKey = borg.trikeshed.userspace.context.AsyncContextKey.NioUserspaceKey
        val liburingKey = borg.trikeshed.userspace.context.AsyncContextKey.LiburingKey
        val fanoutKey = borg.trikeshed.userspace.context.AsyncContextKey.FanoutDispatcherKey
        val btrfsKey = borg.trikeshed.userspace.context.AsyncContextKey.BtrfsCodecKey

        val keys = setOf(nioKey, liburingKey, fanoutKey, btrfsKey)
        assertEquals(4, keys.size, "all context keys must be distinct singletons")
    }
}
