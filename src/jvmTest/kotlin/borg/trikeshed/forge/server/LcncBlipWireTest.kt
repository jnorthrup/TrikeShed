package borg.trikeshed.forge.server

import borg.trikeshed.dag.*
import borg.trikeshed.lcnc.PanelFacts
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyMap
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LcncBlipWireTest {
    @Test fun emptyPlaneStillLoadsEveryRowProjection() = runBlocking {
        val wire = LcncBlipWire(ReteNetwork(), { emptyList() }, { emptyList() })
        val response = assertNotNull(wire.route("GET", "/api/lcnc/blip?type=json.value", "", null))
        assertEquals(200, response.status)
        val body = reifyMap(response.body)
        assertEquals(false, (body["asserted"] as Map<*, *>)["onPlane"])
        assertEquals(emptyList<Any>(), (body["graal"] as Map<*, *>)["jvm"])
    }

    @Test fun rowsRetainAssertedInferredAndGraalProvenance() = runBlocking {
        val network = ReteNetwork()
        val facts = s_[
            PlaneFacts.fact(PlaneFacts.PANELS, "demo/node/n1", mapOf(
                PlaneFacts.KEY to "demo", PlaneFacts.KIND to PanelFacts.KIND_NODE,
                "node" to "n1", "type" to "json.value", "enabled" to false)),
            PlaneFacts.fact(PlaneFacts.PANELS, "demo/violation/0", mapOf(
                PlaneFacts.KEY to "demo", PlaneFacts.KIND to PanelFacts.KIND_VIOLATION,
                "fromNode" to "n1", "toNode" to "n2")),
            PlaneFacts.fact(PlaneFacts.GRAAL, "pointcut/n1", mapOf(
                PlaneFacts.KIND to "pointcut", "node" to "n1", "mark" to 7)),
            PlaneFacts.fact(PlaneFacts.GRAAL, "jvm/memory", mapOf(PlaneFacts.KIND to "memory", "used" to 100)),
            PlaneFacts.fact(PlaneFacts.PANELS, "other/node/n1", mapOf(
                PlaneFacts.KEY to "other", PlaneFacts.KIND to PanelFacts.KIND_NODE, "node" to "n1")),
            PlaneFacts.fact("metadata", "unrelated/n1", mapOf(
                PlaneFacts.KEY to "demo", PlaneFacts.KIND to PanelFacts.KIND_NODE, "node" to "n1")),
        ]
        for (fact in facts.view) network.assert(fact.factId, fact.fields, fact.versionCid, fact.board)
        val wire = LcncBlipWire(network, { emptyList() }, { emptyList() })
        val response = assertNotNull(wire.route("GET", "/api/lcnc/blip?program=demo&node=n1", "", null))
        assertEquals(200, response.status)
        val body = reifyMap(response.body)
        assertEquals("json.value", body["type"])
        fun row(section: String, field: String): Map<*, *> =
            ((body[section] as Map<*, *>)[field] as List<*>).single() as Map<*, *>
        assertEquals("demo/node/n1", row("asserted", "facts")["id"])
        assertEquals(false, (row("asserted", "facts")["fields"] as Map<*, *>)["enabled"])
        assertEquals("demo/violation/0", row("inferred", "violations")["id"])
        assertEquals("pointcut/n1", row("graal", "keyed")["id"])
        assertEquals("jvm/memory", row("graal", "jvm")["id"])
        assertEquals(facts.size, network.snapshot().size, "the hover projection must not remove any facts")
    }
}
