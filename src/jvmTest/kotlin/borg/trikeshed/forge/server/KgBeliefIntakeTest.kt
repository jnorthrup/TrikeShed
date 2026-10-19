package borg.trikeshed.forge.server

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.context.ElementState
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.reify
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class KgBeliefIntakeTest {
    private class GatedLog(val fail: Boolean = false) : DurableAppendLog {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun append(sequence: Long, payload: ByteArray): Long {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "fixture append not released" }
            check(!fail) { "fixture append failed" }
            return sequence
        }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long = 0L
        override fun flush() {}
        override fun injectCorruptionAfter(sequence: Long) = error("unused")
    }

    @Test @org.junit.jupiter.api.Timeout(15)
    fun httpReportWaitsForProcessingAndDrainJoinsTheAdmittedWrite() = runBlocking {
        val log = GatedLog()
        val bag = BeliefBagElement(wal = log)
        bag.open()
        try {
            val wire = BeliefWire(bag, TurnReviewElement(bag), null)
            val body = "(subclass Sparrow Bird)"
            val request = async(Dispatchers.Default) {
                wire.route("POST", "/api/beliefs/kg", "POST /api/beliefs/kg HTTP/1.1\r\n\r\n$body", null)!!
            }
            assertTrue(log.entered.await(5, TimeUnit.SECONDS))
            assertFalse(request.isCompleted, "enqueued is not processed")
            val draining = async(Dispatchers.Default) { bag.drain() }
            log.release.countDown()
            val response = request.await()
            draining.await()
            assertEquals(200, response.status)
            assertEquals(1, ((reify(response.body) as Map<*, *>)["minted"] as Number).toInt())
            assertNotNull(bag.signalOf(KgNalBridge.bridge(body).single().signal("source").angular))
            assertEquals(ElementState.CLOSED, bag.state)
        } finally {
            log.release.countDown()
            bag.drain()
        }
    }

    @Test @org.junit.jupiter.api.Timeout(15)
    fun appendFailureReachesCallerWithoutACompletedReport() = runBlocking {
        val log = GatedLog(fail = true).also { it.release.countDown() }
        val bag = BeliefBagElement(wal = log)
        bag.open()
        try {
            val failure = assertFailsWith<IllegalStateException> { bag.ingestKg("(subclass Sparrow Bird)") }
            assertTrue(failure.message.orEmpty().contains("fixture append failed"))
        } finally { bag.drain() }
    }

    @Test @org.junit.jupiter.api.Timeout(15)
    fun cancelledCallerDoesNotDiscardAdmittedWork() = runBlocking {
        val log = GatedLog()
        val bag = BeliefBagElement(wal = log)
        bag.open()
        try {
            val request = launch(Dispatchers.Default) { bag.ingestKg("(subclass Sparrow Bird)") }
            assertTrue(log.entered.await(5, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            log.release.countDown()
            bag.drain()
            assertEquals(1, bag.size)
            assertEquals(ElementState.CLOSED, bag.state)
        } finally {
            log.release.countDown()
            bag.drain()
        }
    }

    @Test fun httpAndNodeHaveEquivalentReportsAndBlankInputRefuses() = runBlocking {
        val httpBag = BeliefBagElement()
        val nodeBag = BeliefBagElement()
        httpBag.open()
        nodeBag.open()
        try {
            val wire = BeliefWire(httpBag, TurnReviewElement(httpBag), null)
            val text = "ex:rain ex:causes ex:wet ."
            val response = wire.route("POST", "/api/beliefs/kg", text, null)!!
            val runner = borg.trikeshed.lcnc.KgNodes.registry { nodeBag.ingestKg(it) }.getValue("kg.ingest")
            val report = runner.run(borg.trikeshed.lcnc.LcncNode("kg", "kg.ingest", mapOf("kg" to text)), emptyMap())["report"]
            assertEquals(borg.trikeshed.parse.jsonOf(report), response.body)
            val angular = KgNalBridge.bridge(text).single().signal("source").angular
            assertEquals(1, httpBag.size)
            assertEquals(httpBag.size, nodeBag.size)
            assertNotNull(httpBag.signalOf(angular))
            assertEquals(httpBag.signalOf(angular), nodeBag.signalOf(angular))
            assertEquals(httpBag.budgetOf(angular), nodeBag.budgetOf(angular))
            assertEquals(httpBag.glossOf(angular), nodeBag.glossOf(angular))
            assertEquals(400, wire.route("POST", "/api/beliefs/kg", " ", null)!!.status)
        } finally { httpBag.drain(); nodeBag.drain() }
    }
}
