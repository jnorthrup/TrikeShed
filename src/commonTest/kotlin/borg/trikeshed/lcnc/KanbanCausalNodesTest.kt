package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.jsonOf

/**
 * Proves an LCNC node calling `CausalityRete` in-process actually fires —
 * real NARS evidence math through the graph interpreter, not a mock and not
 * an HTTP round trip a browser-JS node would have needed instead.
 */
class KanbanCausalNodesTest {

    @Test
    fun multipleSourceCandidatesKeepPolarityAndProvenanceInHypotheticalFirings() = runTest {
        val positive = EternalRule("Delays", "cancellations", NalCopula.IMPLICATION, EvidenceCoord(900, 0), "source-a")
        val negative = positive.copy(evidence = EvidenceCoord(0, 900), provenanceCid = "source-b")
        val equivalence = EternalRule("ready", "reviewed", NalCopula.EQUIVALENCE, EvidenceCoord(800, 0), "source-c")
        val temporal = positive.copy(copula = NalCopula.PREDICTIVE_IMPLICATION)
        val rules = listOf(positive, negative, equivalence, temporal).map { rule -> mapOf(
            "antecedent" to rule.antecedent, "consequent" to rule.consequent, "copula" to rule.copula.symbol,
            "positiveEvidence" to rule.evidence.positive, "negativeEvidence" to rule.evidence.negative,
            "provenanceCid" to rule.provenanceCid, "ruleCid" to rule.ruleCid.value, "quote" to "retained quote") }
        val assumptions = listOf(mapOf("subject" to "Delays", "obj" to "party-a"),
            mapOf("subject" to "Delays", "obj" to "party-b"), mapOf("subject" to "reviewed", "obj" to "reviewer"))
        val runner = KanbanCausalNodes.runner()
        val node = LcncNode("simulate", "nars.reteFire", mapOf("discount" to "0.5"))
        val output = runner.run(node, mapOf("rules?" to jsonOf(rules), "assertions?" to assumptions))
        assertEquals(true, output["hypothetical"])
        assertEquals(output - "evaluation", output["evaluation"], "Prompt consumers receive the actual evaluation, including rejected rules")
        assertEquals(0.5f, output["discount"])
        assertEquals(assumptions, output["assertions"])
        assertEquals(1, output["rejected"])
        assertEquals(listOf(positive.ruleCid.value, negative.ruleCid.value, equivalence.ruleCid.value), output["ruleCids"])
        val firings = (output["firings"] as List<*>).map { it as Map<*, *> }
        assertEquals(5, firings.size)
        for (party in listOf("party-a", "party-b")) {
            val pair = firings.filter { it["matchedObject"] == party }
            assertEquals(listOf(450L, 0L), pair.map { it["supportPositive"] })
            assertEquals(listOf(0L, 450L), pair.map { it["supportNegative"] })
            assertEquals(listOf("source-a", "source-b"), pair.map { it["provenanceCid"] })
            assertTrue(pair.all { it["hypothetical"] == true && it["dependence"] == "DEPENDENT" })
        }
        val reverse = firings.last()
        assertEquals("ready", reverse["consequent"])
        assertEquals(equivalence.ruleCid.value, reverse["ruleCid"])
        assertEquals(output, runner.run(node, mapOf("rules" to rules, "assertions" to assumptions)))
        // Empty wired candidates override defaults, rather than inventing an unwired rule.
        val empty = runner.run(node.copy(params = mapOf("antecedent" to "Delays", "consequent" to "cancellations")),
            mapOf("rules" to emptyList<Any?>(), "assertions" to assumptions))
        assertTrue((empty["firings"] as List<*>).isEmpty())
    }

    @Test
    fun invalidSimulationInputsAreNotCoercedIntoCausalEvidence() = runTest {
        val runner = KanbanCausalNodes.runner()
        val params = mapOf("antecedent" to "a", "consequent" to "b")
        for (bad in listOf("wat", "NaN", "Infinity", "-1", "1.1")) assertFailsWith<IllegalArgumentException> {
            runner.run(LcncNode("r", "nars.reteFire", params + ("discount" to bad)), emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            runner.run(LcncNode("r", "nars.reteFire", params + ("copula" to "unknown")), emptyMap())
        }
        for (bad in listOf("not an array", listOf("not an object"), listOf(mapOf("obj" to "b")))) {
            assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("r", "nars.reteFire", params), mapOf("assertions" to bad)) }
        }
    }

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
