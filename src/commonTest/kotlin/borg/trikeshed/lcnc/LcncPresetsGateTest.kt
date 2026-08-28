package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every offered preset must be loadable, not performative: it parses through
 * the same Confix seam the browser uses, every node type has a contract (the
 * one vocabulary), and every wire references real nodes and declared ports.
 * preset-kanban is the step-5 sample — the board as a composition — so this
 * gate is what keeps "kanban as LCNC" from regressing into theater.
 */
class LcncPresetsGateTest {

    @Test
    fun everyPresetParsesAndSpeaksTheOneVocabulary() {
        val contracts = LcncContracts.all().associateBy { it.type }
        val presets = LcncPresets.all()
        assertTrue("preset-kanban" in presets, "the step-5 kanban composition is offered")
        for ((name, json) in presets) {
            val program = LcncProgramConfix.fromJson(name, json)
            assertTrue(program.nodes.size > 0, "$name: has nodes")
            val ids = HashSet<String>()
            for (i in 0 until program.nodes.size) {
                val n = program.nodes[i]
                ids.add(n.id)
                assertTrue(n.type in contracts, "$name/${n.id}: type '${n.type}' has no contract")
            }
            for (i in 0 until program.wires.size) {
                val w = program.wires[i]
                assertTrue(w.fromNode in ids && w.toNode in ids,
                    "$name: wire ${w.fromNode}->${w.toNode} references a missing node")
                val from = contracts.getValue(nodeType(program, w.fromNode))
                val to = contracts.getValue(nodeType(program, w.toNode))
                assertTrue(from.outputs.any { it.removeSuffix("?") == w.fromPort.removeSuffix("?") },
                    "$name: ${w.fromNode}.${w.fromPort} is not a declared output of ${from.type}")
                assertTrue(to.inputs.any { it.removeSuffix("?") == w.toPort.removeSuffix("?") },
                    "$name: ${w.toNode}.${w.toPort} is not a declared input of ${to.type}")
            }
        }
    }

    private fun nodeType(program: LcncProgram, id: String): String {
        for (i in 0 until program.nodes.size) if (program.nodes[i].id == id) return program.nodes[i].type
        error("node $id not found")
    }
}
