package borg.trikeshed.dag

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReteAdmissionTest {
    private fun network(capacity: Int = 256): ReteNetwork = ReteNetwork(traceCapacity = capacity).also { net ->
        net.register(object : ReteProduction {
            override val ruleId = "evidence-probe"
            override val salience = 1
            override val interests: Series<Join<String, Any?>> = 1 j { _: Int -> "kind" j ("probe" as Any?) }
            override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
                for (fact in net.workingMemory.query(BlackboardContext(partitionId), "kind" to "probe")) {
                    fire(Activation(fact.versionCid.value, "probe-match", ContentId.of("rule".encodeToByteArray()),
                        1, 1, listOf(fact.versionCid), mapOf("id" to fact.factId.localId)))
                }
            }
        })
    }

    private suspend fun ReteNetwork.tell(id: String) {
        val f = PlaneFacts.fact("probe", id, mapOf("kind" to "probe", "id" to id))
        assert(f.factId, f.fields, f.versionCid, f.board)
    }

    @Test
    fun admissionsRecordRealSupportAndRefractionDoesNotInventDuplicates() = runBlocking {
        val net = network()
        net.tell("a")
        net.tell("a")
        net.evaluateRules("probe")
        val snapshot = net.admissionSnapshot()
        assertEquals(1L, snapshot.admitted)
        val r = snapshot.receipts.single()
        assertEquals("evidence-probe", r.productionId)
        assertEquals("probe-match", r.activation.ruleId)
        assertEquals("probe", r.partitionId)
        assertEquals("no-sink", r.delivery)
        assertEquals(net.snapshot().single().versionCid, r.activation.supportCids.single())
    }

    @Test
    fun boundedHistoryReportsEvictionAndSurvivesFactRetraction() = runBlocking {
        val net = network(2)
        net.tell("a"); net.tell("b"); net.tell("c")
        net.retract(FactId("probe", "b"))
        val s = net.admissionSnapshot()
        assertEquals(3L, s.admitted)
        assertEquals(1L, s.dropped)
        assertEquals(listOf(2L, 3L), s.receipts.map { it.ordinal })
        assertEquals(listOf("b", "c"), s.receipts.map { it.activation.bindings["id"] })
    }

    @Test
    fun sinkSuccessAndFailureAreNotReportedAsCompletedActions() = runBlocking {
        val net = network()
        net.productionSink = { }
        net.tell("ok")
        net.productionSink = { error("sink failed") }
        assertFailsWith<IllegalStateException> { net.tell("bad") }
        assertEquals(listOf("sink-returned", "delivery-failed"), net.admissionSnapshot().receipts.map { it.delivery })
    }

    @Test
    fun zeroCapacityRetainsNoReceiptsAndNegativeCapacityIsRejected() = runBlocking {
        val net = network(0)
        net.tell("a")
        assertEquals(1L, net.admissionSnapshot().dropped)
        assertEquals(emptyList(), net.admissionSnapshot().receipts)
        assertFailsWith<IllegalArgumentException> { network(-1) }
        Unit
    }
}
