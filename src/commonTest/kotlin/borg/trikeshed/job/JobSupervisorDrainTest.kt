package borg.trikeshed.job

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
