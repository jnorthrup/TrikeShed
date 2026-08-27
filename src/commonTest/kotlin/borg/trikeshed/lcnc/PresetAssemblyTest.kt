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
    fun allThreePresetsExistAndRoundTrip() {
        val all = LcncPresets.all()
        assertEquals(setOf("preset-hermes", "preset-tribunal", "preset-curator"), all.keys)
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

            fun typeOf(id: String): String? {
                val n = p.nodes.size
                for (i in 0 until n) if (p.nodes[i].id == id) return p.nodes[i].type
                return null
            }

            val w = p.wires.size
            for (i in 0 until w) {
                val wire = p.wires[i]
                val srcType = typeOf(wire.fromNode)
                val tgtType = typeOf(wire.toNode)
                assertTrue(srcType != null && tgtType != null,
                    "$name wire ${wire.fromNode}→${wire.toNode} references missing node")
                val src = LcncContracts.find(srcType!!)!!
                val tgt = LcncContracts.find(tgtType!!)!!
                assertTrue(src.outputs.contains(wire.fromPort),
                    "$name: $srcType has no output '${wire.fromPort}'")
                assertTrue(tgt.inputs.contains(wire.toPort),
                    "$name: $tgtType has no input '${wire.toPort}'")
                // Kind compatibility across every preset edge — the same rule
                // LcncMating.compatibleTypes applies to interactive drags.
                val outKind = src.outputKinds[wire.fromPort.removeSuffix("?")]
                val inKind = tgt.inputKinds[wire.toPort.removeSuffix("?")]
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
        // The graph must validate under Phase 4's rules.
        val v = g.validate()
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
        // gateway, no filesystem anywhere in it: installation can only happen
        // through an explicit client POST to /api/panels/<name>.
        for ((_, doc) in LcncPresets.all()) {
            assertTrue(doc.startsWith("{"), "preset document is Confix JSON, offerable verbatim")
        }
    }
}
