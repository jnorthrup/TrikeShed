package borg.trikeshed.lcnc

import borg.trikeshed.parse.*
import kotlin.test.*

class OroborosEvidenceTest {
    @Test
    fun escapedBurstFitsContentBudgetAndRetainsNewestDetail() {
        val events = List(256) { mapOf("sequence" to it, "after" to mapOf("value" to "\u0001".repeat(8192))) }
        val bytes = oroborosEvidence("run", "cid", mapOf(
            "state" to "completed",
            "actions" to emptyList<String>(),
            "interventions" to listOf(mapOf("sequence" to 1, "status" to "applied")),
            "monitor" to mapOf("events" to events, "observed" to 256, "complete" to true),
        ))
        assertTrue(bytes.size <= 1_048_576)
        val evidence = reify(bytes) as Map<*, *>
        assertNull(ValueBudget(maxMillis = 1000).violation(evidence))
        assertEquals("run", evidence["runId"])
        val debug = evidence["debug"] as Map<*, *>
        val monitor = debug["monitor"] as Map<*, *>
        val retained = monitor["events"] as List<*>
        assertTrue(retained.isNotEmpty())
        assertEquals(255, (((retained.last() as Map<*, *>)["sequence"]) as Number).toInt())
        assertEquals(256, retained.size + (monitor["eventsOmitted"] as Number).toInt())
        assertEquals(false, monitor["complete"])
        assertEquals(1, (debug["interventions"] as List<*>).size)
    }

    @Test
    fun excessiveSourceStateIsExplicitlyOmittedBeforeInterventions() {
        val bytes = oroborosEvidence("run", "cid", mapOf(
            "state" to "failed",
            "site" to mapOf("nodeId" to "code", "code" to "界".repeat(200000)),
            "interventions" to listOf(mapOf("status" to "applied")),
        ))
        val debug = (reify(bytes) as Map<*, *>)["debug"] as Map<*, *>
        assertNotNull(debug["stateOmitted"])
        assertEquals("code", (debug["site"] as Map<*, *>)["nodeId"])
        assertEquals(1, (debug["interventions"] as List<*>).size)
    }

    @Test
    fun unsupportedStateNeverInvokesItsStringRenderer() {
        val opaque = object {
            override fun toString(): String = error("guest renderer must not run")
        }
        val bytes = oroborosEvidence("run", "cid", mapOf("state" to "failed", "opaque" to opaque))
        val debug = (reify(bytes) as Map<*, *>)["debug"] as Map<*, *>
        assertEquals("failed", debug["state"])
        assertNotNull(debug["stateOmitted"])
    }
}
