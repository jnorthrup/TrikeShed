
package borg.trikeshed.jules

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettlementBarrierTest {

    @Test
    fun testIdempotentRelease() = runTest {
        val barrier = SettlementBarrier()
        val release = barrier.enter()
        assertEquals(1, barrier.pendingTurns)

        release()
        assertEquals(0, barrier.pendingTurns)

        // Idempotent release
        release()
        assertEquals(0, barrier.pendingTurns)
    }

    @Test
    fun testAdmissionClosedDuringSettlement() = runTest {
        val barrier = SettlementBarrier()
        val release = barrier.enter()

        val settleJob = launch {
            barrier.awaitSettlement(1000)
        }
        runCurrent()

        // Admission should be closed
        val ex = assertFailsWith<IllegalStateException> {
            barrier.enter()
        }
        assertTrue(ex.message!!.contains("settlement sequence in progress"))

        release()
        settleJob.join()

        // Admission reopened
        barrier.enter()
        assertEquals(1, barrier.pendingTurns)
    }

    @Test
    fun testSuccessfulDrain() = runTest {
        val barrier = SettlementBarrier()
        val release1 = barrier.enter()
        val release2 = barrier.enter()
        assertEquals(2, barrier.pendingTurns)

        var settled = false
        val settleJob = launch {
            settled = barrier.awaitSettlement(1000)
        }
        runCurrent()

        assertFalse(settled)
        release1()
        runCurrent()
        assertFalse(settled)

        release2()
        runCurrent()
        assertTrue(settled)
    }

    @Test
    fun testTimeout() = runTest {
        val barrier = SettlementBarrier()
        barrier.enter() // Acquired but not released

        val settled = barrier.awaitSettlement(500) // Small timeout
        assertFalse(settled)
    }

    @Test
    fun testFlushBeforeEvictOrder() = runTest {
        val barrier = SettlementBarrier()
        val order = mutableListOf<String>()

        val settled = evictStalledSession(
            "test-session",
            barrier,
            flushIndex = { order.add("flush") },
            evict = { order.add("evict") }
        )

        assertTrue(settled)
        assertEquals(listOf("flush", "evict"), order)
    }

    @Test
    fun testConcurrentAnswerTurnCompletion() = runTest {
        val barrier = SettlementBarrier()
        val release1 = barrier.enter()

        var settled = false
        val settleJob = launch {
            settled = barrier.awaitSettlement(1000)
        }
        runCurrent()

        // Now release after settlement has started waiting
        release1()
        runCurrent()

        assertTrue(settled)
    }
}
