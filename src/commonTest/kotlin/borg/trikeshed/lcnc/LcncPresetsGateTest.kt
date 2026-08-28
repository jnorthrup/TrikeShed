package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every offered preset must be loadable, not performative: it parses through
 * the Confix seam, every node type has a contract (the one vocabulary), and
 * every wire references real nodes and declared ports — RECURSIVELY through
 * the rings, because a preset is a concentric document (contract line 1).
 * A ring's real ports are declared by its body: outputs = its scope.out
 * names (+ returns), inputs = args?/when? + its scope.in names.
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

            val byId = HashMap<String, LcncNode>()
            fun walk(nodes: Series<LcncNode>) {
                for (i in 0 until nodes.size) {
                    val n = nodes[i]
                    assertTrue(byId.put(n.id, n) == null, "$name: duplicate node id '${n.id}'")
                    assertTrue(n.type in contracts, "$name/${n.id}: type '${n.type}' has no contract")
                    if (n.children.size > 0) walk(n.children)
                }
            }
            walk(program.nodes)

            for (i in 0 until program.wires.size) {
                val w = program.wires[i]
                val from = byId[w.fromNode]
                val to = byId[w.toNode]
                assertTrue(from != null && to != null,
                    "$name: wire ${w.fromNode}->${w.toNode} references a missing node")
                assertTrue(outputsOf(from!!, contracts).any { it.removeSuffix("?") == w.fromPort.removeSuffix("?") },
                    "$name: ${w.fromNode}.${w.fromPort} is not a declared output of ${from.type}")
                assertTrue(inputsOf(to!!, contracts).any { it.removeSuffix("?") == w.toPort.removeSuffix("?") },
                    "$name: ${w.toNode}.${w.toPort} is not a declared input of ${to.type}")
            }
        }
    }

    /** A ring's outputs are its body's scope.out names plus the composed returns port. */
    private fun outputsOf(n: LcncNode, contracts: Map<String, LcncPortContract>): List<String> =
        if (n.children.size > 0) {
            val names = ArrayList<String>()
            for (i in 0 until n.children.size) {
                val c = n.children[i]
                if (c.type == LcncContracts.SCOPE_OUT) c.params["name"]?.let { names.add(it) }
            }
            names + "returns"
        } else contracts.getValue(n.type).outputs

    /** A ring's inputs are args?/when? plus its body's scope.in names. */
    private fun inputsOf(n: LcncNode, contracts: Map<String, LcncPortContract>): List<String> =
        if (n.children.size > 0) {
            val names = arrayListOf("args?", "when?")
            for (i in 0 until n.children.size) {
                val c = n.children[i]
                if (c.type == LcncContracts.SCOPE_IN) c.params["name"]?.let { names.add(it) }
            }
            names
        } else contracts.getValue(n.type).inputs
}
