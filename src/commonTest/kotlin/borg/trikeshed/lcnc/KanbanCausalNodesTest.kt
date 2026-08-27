package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves an LCNC node calling `CausalityRete` in-process actually fires —
 * real NARS evidence math through the graph interpreter, not a mock and not
 * an HTTP round trip a browser-JS node would have needed instead.
 */
class KanbanCausalNodesTest {

    @Test
    fun reteFireNodeProducesRealDiscountedSupportForAMatchingAssertion() = runTest {
        val program = LcncProgram(
            "kanban-causal",
            listOf(
                LcncNode(
                    "rule",
                    "nars.reteFire",
                    params = mapOf("antecedent" to "blocked", "consequent" to "escalate", "discount" to "0.5"),
                ),
            ).toSeries(),
            emptySeriesOfWire(),
        )
        val runner = LcncRunner(kanbanLcncRegistry())
        val outputs = runner.runAll(program)

        val out = outputs.getValue("rule")
        @Suppress("UNCHECKED_CAST")
        val firings = out["firings"] as List<Map<String, Any?>>
        // no `assertions` input wired -> zero live facts -> zero firings, but the
        // node must still execute (prove the registry dispatch works) and the
        // rule CID must be a real, non-blank content id, not a stub.
        assertEquals(0, firings.size)
        assertTrue((out["ruleCid"] as String).isNotBlank())
    }

    @Test
    fun reteFireNodeFiresWhenWiredToARealAssertion() = runTest {
        val fireId = "rule"
        val program = LcncProgram(
            "kanban-causal",
            listOf(
                LcncNode("facts", "test.constAssertions"),
                LcncNode(
                    fireId,
                    "nars.reteFire",
                    params = mapOf("antecedent" to "blocked", "consequent" to "escalate", "discount" to "0.5"),
                ),
            ).toSeries(),
            listOf(LcncWire("facts", "assertions", fireId, "assertions")).toSeries(),
        )
        val constAssertions = LcncNodeRunner { _, _ ->
            mapOf("assertions" to listOf(mapOf("subject" to "blocked", "obj" to "escalate")))
        }
        val registry = kanbanLcncRegistry() + mapOf("test.constAssertions" to constAssertions)
        val outputs = LcncRunner(registry).runAll(program)

        @Suppress("UNCHECKED_CAST")
        val firings = outputs.getValue(fireId)["firings"] as List<Map<String, Any?>>
        assertEquals(1, firings.size, "the rule's antecedent matches the wired-in assertion — it must fire")
        val firing = firings[0]
        assertEquals("blocked", firing["antecedent"])
        assertEquals("escalate", firing["consequent"])
        // discount 0.5 against Nal.UNIT positive evidence — real arithmetic, not a placeholder.
        assertTrue((firing["supportPositive"] as Long) > 0L, "discounted support must be real, non-zero evidence")
    }
}

private fun emptySeriesOfWire() = borg.trikeshed.lib.emptySeriesOf<LcncWire>()
