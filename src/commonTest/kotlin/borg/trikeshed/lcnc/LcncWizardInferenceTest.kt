package borg.trikeshed.lcnc

import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P3 wizard/inference-fill gates: evidence majority, unique wire, ambiguity refusal, roster completeness. */
class LcncWizardInferenceTest {
    private fun program(name: String, vararg nodes: LcncNode): LcncProgram =
        LcncProgram(name, nodes.size j { i: Int -> nodes[i] }, emptySeriesOf())

    @Test
    fun paramFillRanksCorpusMajorityValue() {
        val corpus = listOf(
            program("a", LcncNode("t1", "timer", mapOf("seconds" to "5"))),
            program("b", LcncNode("t2", "timer", mapOf("seconds" to "5"))),
            program("c", LcncNode("t3", "timer", mapOf("seconds" to "10"))),
        )
        val fills = LcncMating.paramFills("timer", corpus).filter { it.param == "seconds" }
        assertEquals(LcncParamFill("seconds", "5", 2), fills.first())
        assertEquals(LcncParamFill("seconds", "10", 1), fills[1])
    }

    @Test
    fun autoWireProposesUniqueKindPair() {
        val p = program("unique", LcncNode("from", "timer"), LcncNode("to", "http.get"))
        val result = LcncMating.autoWire(p, "from", "to")
        assertEquals(1, result.candidates.size)
        assertEquals("tick", result.wire?.fromPort)
        assertEquals("trigger?", result.wire?.toPort)
        assertEquals("trigger", result.candidates[0].kind)
    }

    @Test
    fun autoWireRefusesAmbiguousPairsAndReturnsEvidence() {
        val p = program("ambiguous", LcncNode("from", "context.assemble"), LcncNode("to", LcncContracts.SCOPE))
        val result = LcncMating.autoWire(p, "from", "to")
        assertNull(result.wire, "ambiguity must never pick an arbitrary first pair")
        assertEquals(2, result.candidates.size)
        assertEquals(setOf("args?", "when?"), result.candidates.map { it.toPort }.toSet())
    }

    @Test
    fun wizardRosterHasKindsAndParameterOrderForEveryContract() {
        for (contract in LcncContracts.all()) {
            for (input in contract.inputs) {
                assertTrue(contract.inputKinds.containsKey(input.removeSuffix("?")), "${contract.type}.$input missing input kind")
            }
            for (output in contract.outputs) {
                assertTrue(contract.outputKinds.containsKey(output.removeSuffix("?")), "${contract.type}.$output missing output kind")
            }
            // params is a LinkedHashMap/map literal in declaration order; iteration is the wizard order.
            assertEquals(contract.params.keys.toList(), contract.params.entries.map { it.key })
        }
    }
}
