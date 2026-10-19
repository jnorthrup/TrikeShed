package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.vm.VmLanguage
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** The authored row view is presentation; the JSON literal and Rete contract remain the authority. */
class LcncAssumptionsTest {
    @Test
    fun columnSelectionIsExplicitAndDoesNotRepairInvalidDeclarations() {
        val spec = LcncContracts.all().single { it.type == "json.value" }.params.getValue("value")
        assertEquals("columns", spec.colsParam)
        assertEquals(emptyList<String>(), spec.columns(emptyMap()))
        assertEquals(emptyList<String>(), spec.columns(mapOf("columns" to "")))
        assertEquals(listOf("subject", "obj"), spec.columns(mapOf("columns" to " subject, obj ")))
        for (bad in listOf("subject,subject", "subject,,obj", "subject,", (1..65).joinToString(","))) {
            assertFailsWith<IllegalArgumentException> { spec.columns(mapOf("columns" to bad)) }
        }
        assertEquals(listOf("key", "model"), LcncPortContract.LcncParamSpec(cols = listOf("key", "model")).columns(emptyMap()))
    }

    @Test
    fun assumptionsDeclareColumnsWithoutChangingLiteralOrReceipt() {
        val value = """[{"subject":"Delays","obj":"reviewer","hypothetical":true,"extra":{"score":0.75}}]"""
        @Suppress("UNCHECKED_CAST")
        val assertions = reify(value) as List<Map<String, Any?>>
        val programs = listOf(
            LcncWorkspacePresets.simulation("retained-cid", assertions),
            LcncWorkspacePresets.deliberation("retained-cid", assertions),
            LcncWorkspacePresets.routing(VmLanguage.KOTLIN_COMMON, "retained-cid", assertions),
        ) + listOf(LcncWorkspacePresets.MEDICAL, LcncWorkspacePresets.LEGAL).map { name ->
            LcncProgramConfix.fromJson(name, LcncWorkspacePresets.documents().getValue(name))
        }
        for (program in programs) {
            val node = program.nodes.view.single { it.id == "assumptions" }
            assertEquals("json.value", node.type)
            assertEquals("subject,obj", node.params["columns"], program.name)
            val restored = LcncProgramConfix.fromJson(program.name, LcncProgramConfix.toJson(program))
            assertEquals(node.params, restored.nodes.view.single { it.id == node.id }.params)
            assertEquals("value", program.wires.view.single { it.fromNode == node.id }.fromPort)
        }
        assertEquals(assertions, reify(programs.first().nodes.view.single { it.id == "assumptions" }.params.getValue("value")))
    }

    @Test
    fun columnsDoNotCoerceLiteralValuesOrBecomeEvidence() = runTest {
        val value = """[{"subject":"Delays","obj":"reviewer","hypothetical":true,"extra":{"score":0.75}}]"""
        val literal = LcncNode("assumptions", "json.value", mapOf("value" to value, "columns" to "subject,obj"))
        val output = PureNodes.registry { 0L }.getValue(literal.type).run(literal, emptyMap())
        assertEquals(mapOf("value" to reify(value)), output)
        val simulation = LcncNode("simulation", "nars.reteFire", mapOf("antecedent" to "Delays", "consequent" to "cancellations"))
        val evaluation = KanbanCausalNodes.runner().run(simulation, mapOf("assertions?" to output.getValue("value")))
        assertEquals(reify(value), evaluation["assertions"])
        val firing = (evaluation["firings"] as List<*>).single() as Map<*, *>
        assertEquals("reviewer", firing["matchedObject"])
        assertEquals(true, firing["hypothetical"])
        assertEquals("DEPENDENT", firing["dependence"])
        val empty = KanbanCausalNodes.runner().run(simulation, mapOf("assertions?" to emptyList<Any?>()))
        assertEquals(emptyList<Any?>(), empty["firings"])
    }
}
