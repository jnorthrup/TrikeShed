package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.BudgetCoord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class KgIngestTest {
    @Test fun turtleAndKifUseTheSameBagAndAcknowledgeProcessedSignals() = runTest {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        try {
            val glosses = mutableMapOf<Long, String>()
            val registry = KgNodes.registry { text -> bag.ingestKg(text) { angular, gloss -> glosses[angular] = gloss } }
            for ((text, format) in listOf("ex:rain ex:causes ex:wet ." to "TURTLE", "(subclass Sparrow Bird)" to "KIF")) {
                val node = LcncNode("kg", "kg.ingest", mapOf("kg" to "fallback"))
                val report = registry.getValue("kg.ingest").run(node, mapOf("text?" to text))["report"] as Map<*, *>
                assertEquals(format, report["format"])
                assertEquals(1, report["statements"])
                assertEquals(1, report["minted"])
                val mapped = KgNalBridge.bridge(text).single()
                val signal = mapped.signal("source")
                assertNotNull(bag.signalOf(signal.angular), "return must follow processing, not just enqueue")
                assertEquals(mapped.gloss(), bag.glossOf(signal.angular))
                assertEquals(mapped.gloss(), glosses[signal.angular])
                assertEquals(mapOf(mapped.copula.name to 1), report["copulas"])
            }
            assertEquals(2, bag.size)
        } finally { bag.drain() }
    }

    @Test fun identicalSourceReplayDoesNotInventIndependentEvidence() = runTest {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        try {
            val text = "(subclass Sparrow Bird)"
            val angular = KgNalBridge.bridge(text).single().signal("source").angular
            bag.ingestKg(text)
            val evidence = bag.signalOf(angular)!!.evidence
            bag.ingestKg(text)
            assertEquals(evidence, bag.signalOf(angular)!!.evidence)
            assertEquals(1, bag.size)
        } finally { bag.drain() }
        assertFailsWith<IllegalStateException> { bag.ingestKg("(subclass Other Bird)") }
    }

    @Test fun blankRefusesBeforeDispatchAndUnknownFormatReportsZero() = runTest {
        var calls = 0
        val runner = KgNodes.registry { calls++; emptyMap() }.getValue("kg.ingest")
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("kg", "kg.ingest"), emptyMap()) }
        assertEquals(0, calls)
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        try {
            val report = bag.ingestKg("unparsed")
            assertEquals(0, report["minted"])
            assertNull(report["format"])
            assertEquals(0, bag.size)
        } finally { bag.drain() }
    }

    @Test fun failedWriterRefusesQueuedAndFutureAcknowledgements() = runTest {
        val failure = IllegalStateException("fixture WAL append failed")
        var appends = 0
        val wal = object : DurableAppendLog {
            override fun append(sequence: Long, payload: ByteArray): Long {
                if (++appends == 1) throw failure
                return sequence
            }
            override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long = 0L
            override fun flush() {}
            override fun injectCorruptionAfter(sequence: Long) = error("unused")
        }
        val bag = BeliefBagElement(capacity = 64, wal = wal)
        val first = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<Unit>()
        val firstSignal = KgNalBridge.bridge("(subclass Sparrow Bird)").single().signal("first")
        val queuedSignal = KgNalBridge.bridge("(subclass Robin Bird)").single().signal("queued")
        val budget = BudgetCoord(0.6f, 0.4f, 0.6f)
        // Buffer both requests before starting the writer so the second is certainly
        // admitted when the first append fails. No clock or scheduler timing is assumed.
        bag.intake.send(BeliefIntake.Mint(firstSignal, budget, completion = first))
        bag.intake.send(BeliefIntake.Mint(queuedSignal, budget, completion = queued))
        bag.open()
        try {
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { first.await() }.message)
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { queued.await() }.message)
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { bag.ingestKg("(subclass Finch Bird)") }.message)
            assertEquals(1, appends, "a failed writer must not continue from partially mutated state")
            assertNull(bag.signalOf(queuedSignal.angular))
        } finally { bag.drain() }
    }

    @Test fun callerServiceWinsAndInputsPrecedeParams() = runTest {
        val runner = KgNodes.registry { error("default must not run") }.getValue("kg.ingest")
        val binding = runner as LcncServiceBinding
        assertEquals(1, binding.requiredKeys.size)
        assertSame(KgIngestKey, binding.requiredKeys[0])
        withContext(KgIngestKey { text -> mapOf("text" to text) }) {
            val node = LcncNode("kg", "kg.ingest", mapOf("kg" to "parameter"))
            assertEquals(mapOf("text" to "wired"), runner.run(node, mapOf("text?" to "wired"))["report"])
            assertEquals(mapOf("text" to "parameter"), runner.run(node, emptyMap())["report"])
        }
    }
}
