package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JVM-executed counterpart of scripts/preset-gate.sh's static prefab pass. */
class PresetRequiredInputsTest {

    @Test
    fun everyRequiredPresetInputIsWiredOrParameterised() {
        val holes = mutableListOf<String>()

        for ((name, document) in LcncPresets.all()) {
            val program = LcncProgramConfix.fromJson(name, document)
            val fed = (0 until program.wires.size)
                .map { program.wires[it] }
                .map { it.toNode to it.toPort.removeSuffix("?") }
                .toSet()

            fun inspect(nodes: borg.trikeshed.lib.Series<LcncNode>) {
                for (i in 0 until nodes.size) {
                    val node = nodes[i]
                    val contract = LcncContracts.find(node.type)
                    if (contract != null) {
                        for (port in contract.inputs.filterNot { it.endsWith("?") }) {
                            val bare = port.removeSuffix("?")
                            if (bare !in node.params && (node.id to bare) !in fed) {
                                holes += "$name ${node.type}.$bare (${node.id})"
                            }
                        }
                    }
                    if (node.children.size > 0) inspect(node.children)
                }
            }

            inspect(program.nodes)
        }

        assertTrue(holes.isEmpty(), "required preset inputs fed by nothing:\n${holes.joinToString("\n")}")
    }

    @Test
    fun resultConfirmAcceptsContentAsSuccessAndStatusAsOptionalRefinement() = runBlocking {
        val contract = LcncContracts.find("result.confirm")!!
        assertEquals(listOf("content", "ok?", "error?", "cached?"), contract.inputs)

        val runner = BrainMuxNodes.registry().getValue("result.confirm")
        val node = LcncNode("confirm", "result.confirm")
        val contentOnly = runner.run(node, mapOf("content" to "folded playbook"))["x"].toString()
        assertTrue("✓ OK" in contentOnly, contentOnly)
        assertTrue("folded playbook" in contentOnly, contentOnly)

        val failed = runner.run(node, mapOf("content" to "", "ok" to false, "error" to "provider failed"))["x"].toString()
        assertTrue("✗ ERROR" in failed, failed)
        assertTrue("provider failed" in failed, failed)
    }
}