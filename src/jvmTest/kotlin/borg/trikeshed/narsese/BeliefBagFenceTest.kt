package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BeliefBagFenceTest {
    private val budget = BudgetCoord(0.6f, 0.5f, 0.5f)
    private fun signal() = SemanticSignal(17L, EvidenceCoord(Nal.UNIT, 0L), RelationKind.MATCH, "subject")
    private fun receipt(text: String) = ContentId.of(text.encodeToByteArray())

    @Test
    fun fenceWaitsForRevisionsEvenWhenBagSizeDoesNotChange() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        try {
            bag.intake.send(BeliefIntake.Mint(signal(), budget, receiptCid = receipt("first")))
            bag.intake.send(BeliefIntake.Mint(signal(), budget, receiptCid = receipt("second")))
            withTimeout(5_000) { bag.awaitProcessed() }
            assertEquals(1, bag.size)
            assertEquals(2 * Nal.UNIT, assertNotNull(bag.signalOf(17L)).evidence.positive)
        } finally { bag.drain() }
    }

    @Test
    fun unopenedAndClosedIntakeRefuseFence() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        assertFailsWith<IllegalStateException> { bag.awaitProcessed() }
        bag.open()
        bag.drain()
        assertFailsWith<IllegalStateException> { bag.awaitProcessed() }
    }

    @Test
    fun failedWriterCompletesQueuedFenceExceptionally() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wal = object : DurableAppendLog {
            override fun append(sequence: Long, payload: ByteArray): Long {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test did not release writer" }
                throw IllegalStateException("synthetic append failure")
            }
            override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long = 0L
            override fun flush() = Unit
            override fun injectCorruptionAfter(sequence: Long) = Unit
        }
        val bag = BeliefBagElement(capacity = 64, wal = wal)
        bag.open()
        try {
            val mint = async(Dispatchers.Default) { runCatching { bag.submit(BeliefIntake.Mint(signal(), budget)) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "writer never reached the injected WAL failure")
            // Enqueue while the single writer is blocked so this cannot pass before the failure.
            val fence = async(start = CoroutineStart.UNDISPATCHED) { runCatching { bag.awaitProcessed() } }
            release.countDown()
            val results = withTimeout(5_000) { mint.await() to fence.await() }
            assertEquals("synthetic append failure", results.first.exceptionOrNull()?.message)
            assertEquals("synthetic append failure", results.second.exceptionOrNull()?.message)
        } finally {
            release.countDown()
            bag.drain()
        }
    }
}
