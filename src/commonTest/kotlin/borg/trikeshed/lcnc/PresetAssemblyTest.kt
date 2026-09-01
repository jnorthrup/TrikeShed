package borg.trikeshed.lcnc

import borg.trikeshed.kanban.KanbanEdgeMode
import borg.trikeshed.kanban.validate
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7 gates: the three pre-assembled orchestrations. Each preset must
 * (a) round-trip through LcncProgramConfix, (b) use ONLY contract-declared
 * node types and ports with matching kinds (else the browser silently drops
 * nodes on load), and (c) be OFFERED not installed — LcncPresets never
 * writes to a store.
 */
class PresetAssemblyTest {

    private fun parsed(name: String): LcncProgram =
        LcncProgramConfix.fromJson(name, LcncPresets.all().getValue(name))

    @Test
    fun allOfferedPresetsExistAndRoundTrip() {
        val all = LcncPresets.all()
        assertEquals(
            setOf(
                "preset-hermes", "preset-tribunal", "preset-curator",
                "preset-context", "preset-kanban", "preset-ccek", "preset-scope", "preset-scope-inner",
                "preset-pairs", "preset-brain-mux", "preset-media",
                "preset-hermes-train", "preset-legal-tribunal", "preset-state-freeze",
                "preset-council", "preset-bughunter", "preset-subvm-audit",
                "preset-turbohaul",
            ),
            all.keys,
        )
        for ((name, doc) in all) {
            val p = LcncProgramConfix.fromJson(name, doc)
            assertEquals(doc, LcncProgramConfix.toJson(p), "$name must round-trip byte-stable")
        }
    }

    @Test
    fun everyPresetNodeTypeIsContractDeclared() {
        for ((name, doc) in LcncPresets.all()) {
            val p = LcncProgramConfix.fromJson(name, doc)
            val n = p.nodes.size
            for (i in 0 until n) {
                val type = p.nodes[i].type
                assertTrue(LcncContracts.find(type) != null,
                    "$name uses type '$type' absent from LcncContracts — browser drops it silently")
            }
        }
    }

    @Test
    fun everyPresetWireConnectsPortsWithMatchingKinds() {
        for ((name, doc) in LcncPresets.all()) {
            val p = LcncProgramConfix.fromJson(name, doc)

            // Concentric documents: the node universe includes ring children.
            val byId = HashMap<String, LcncNode>()
            fun walk(nodes: borg.trikeshed.lib.Series<LcncNode>) {
                for (i in 0 until nodes.size) {
                    val n = nodes[i]
                    byId[n.id] = n
                    if (n.children.size > 0) walk(n.children)
                }
            }
            walk(p.nodes)

            // A ring's ports are declared by its body (contract line 1); its
            // body-derived ports carry no contract kind — the kind check
            // applies only where both sides declare one.
            fun outPorts(n: LcncNode): List<String> =
                if (n.children.size > 0) {
                    (0 until n.children.size).mapNotNull { i ->
                        n.children[i].takeIf { it.type == LcncContracts.SCOPE_OUT }?.params?.get("name")
                    } + "returns"
                } else LcncContracts.find(n.type)!!.outputs
            fun inPorts(n: LcncNode): List<String> =
                if (n.children.size > 0) {
                    listOf("args?", "when?") + (0 until n.children.size).mapNotNull { i ->
                        n.children[i].takeIf { it.type == LcncContracts.SCOPE_IN }?.params?.get("name")
                    }
                } else LcncContracts.find(n.type)!!.inputs

            val w = p.wires.size
            for (i in 0 until w) {
                val wire = p.wires[i]
                val src = byId[wire.fromNode]
                val tgt = byId[wire.toNode]
                assertTrue(src != null && tgt != null,
                    "$name wire ${wire.fromNode}→${wire.toNode} references missing node")
                assertTrue(outPorts(src!!).any { it.removeSuffix("?") == wire.fromPort.removeSuffix("?") },
                    "$name: ${src.type} has no output '${wire.fromPort}'")
                assertTrue(inPorts(tgt!!).any { it.removeSuffix("?") == wire.toPort.removeSuffix("?") },
                    "$name: ${tgt.type} has no input '${wire.toPort}'")
                // Kind compatibility across every preset edge — the same rule
                // LcncMating.compatibleTypes applies to interactive drags.
                val outKind = LcncContracts.find(src.type)?.outputKinds?.get(wire.fromPort.removeSuffix("?"))
                val inKind = LcncContracts.find(tgt.type)?.inputKinds?.get(wire.toPort.removeSuffix("?"))
                if (outKind != null && inKind != null) {
                    assertEquals(outKind, inKind,
                        "$name wire ${wire.fromNode}.${wire.fromPort} → ${wire.toNode}.${wire.toPort} kind mismatch")
                }
            }
        }
    }

    @Test
    fun tribunalCarriesBoundedLoopAbortEdgesAndValidates() {
        val g = parsed("preset-tribunal").kanban
        assertTrue(g != null, "tribunal ships its orchestration graph")
        val edges = (0 until g!!.edges.size).map { g.edges[it] }
        assertTrue(edges.any { it.mode == KanbanEdgeMode.LOOP && it.maxIterations == 3 },
            "argue⇄rebut LOOP bounded at 3")
        assertTrue(edges.any { it.mode == KanbanEdgeMode.ABORT },
            "mistrial ABORT edge present")
        // The graph must validate under Phase 4's rules. The judge's
        // clarification loop-back is a guarded edge — its predicate must be
        // registered for validation to resolve it (an empty registry would
        // report it as unresolved, not illegal).
        val v = g.validate(TribunalPredicates.registry())
        assertTrue(v.valid, "tribunal kanban validates: ${v.errors}")
    }

    @Test
    fun presetsCarryViewportGeometryForRoundTripping() {
        // W2.4 + W6.2 compose: presets ship view/seq so a load restores the camera.
        for ((name, _) in LcncPresets.all()) {
            val p = parsed(name)
            assertTrue(p.view != null, "$name carries a viewport")
            assertTrue(p.seq >= 1, "$name carries a seq")
        }
    }

    @Test
    fun presetsAreOfferedNotInstalled() {
        // LcncPresets' whole surface is name → document text. No store, no
        // gateway, no filesystem anywhere in it: a preset reaches execution
        // only through the stored-program resolver (ModuleContext.programLoader).
        for ((_, doc) in LcncPresets.all()) {
            assertTrue(doc.startsWith("{"), "preset document is Confix JSON, offerable verbatim")
        }
    }
}
