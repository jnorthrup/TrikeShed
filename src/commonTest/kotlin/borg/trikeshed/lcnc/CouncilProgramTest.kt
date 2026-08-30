package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins on the one geometry author (design/legal-council-3x5.md): the default
 * 3x5 convening is deterministic to the byte, exactly the sketched node
 * census, legal under the concentric machine's authored-order and ring-path
 * rules BY CONSTRUCTION, bounded loudly, model-diverse within every panel,
 * case-qualified in its spend contexts, and drawn on a real grid — no node
 * hides at the origin.
 */
class CouncilProgramTest {

    /** A node placed in the document: pre-order index + enclosing ring path. */
    private data class Placed(val node: LcncNode, val path: List<String>, val index: Int)

    private fun flatten(program: LcncProgram): List<Placed> {
        val out = ArrayList<Placed>()
        fun walk(nodes: Series<LcncNode>, path: List<String>) {
            for (i in 0 until nodes.size) {
                val n = nodes[i]
                out.add(Placed(n, path, out.size))
                if (n.children.size > 0) walk(n.children, path + n.id)
            }
        }
        walk(program.nodes, emptyList())
        return out
    }

    private fun isPrefix(a: List<String>, b: List<String>): Boolean =
        a.size <= b.size && a.indices.all { a[it] == b[it] }

    // ── (1) determinism: same config, same bytes ─────────────────────────

    @Test
    fun buildIsDeterministicToTheByte() {
        val first = LcncProgramConfix.toJson(CouncilProgram.build(CouncilConfig.DEFAULT_3x5))
        val second = LcncProgramConfix.toJson(CouncilProgram.build(CouncilConfig.DEFAULT_3x5))
        assertEquals(first, second, "build(DEFAULT_3x5) must emit byte-identical JSON on every call")
    }

    // ── (2) the sketched census: 98 nodes, 34 seats + 4 clarify seats ────

    @Test
    fun defaultConveningMatchesTheSketchedCensus() {
        val placed = flatten(CouncilProgram.build(CouncilConfig.DEFAULT_3x5))
        assertEquals(98, placed.size, "default 3x5x2 convening is 98 nodes")

        val ids = placed.map { it.node.id }
        assertEquals(ids.size, ids.toSet().size, "node ids are document-wide identities")

        val seats = placed.filter { it.node.type == "council.seat" }
        val clarifySeats = seats.filter { "clarify" in it.path }
        val mainSeats = seats.filter { "clarify" !in it.path }
        assertEquals(34, mainSeats.size, "30 expert/rebuttal + 3 synth + 1 ruling outside the clarify ring")
        assertEquals(4, clarifySeats.size, "3 clarify voices + 1 final ruling inside the clarify ring")

        val byRole = mainSeats.groupBy { it.node.params["role"] }
        assertEquals(15, byRole["expert"]?.size, "3 panels x 5 round-1 experts")
        assertEquals(15, byRole["rebuttal"]?.size, "3 panels x 5 round-2 rebuttals")
        assertEquals(3, byRole["synthesis"]?.size, "one synthesis seat per panel")
        assertEquals(1, byRole["ruling"]?.size, "one presiding seat outside the clarify ring")
    }

    // ── (3) legality by construction: authored order + ring-path prefix ──

    @Test
    fun everyWireIsAuthoredOrderAndScopeLegal() {
        val program = CouncilProgram.build(CouncilConfig.DEFAULT_3x5)
        val placed = flatten(program).associateBy { it.node.id }
        for (i in 0 until program.wires.size) {
            val w = program.wires[i]
            val from = placed[w.fromNode]
            val to = placed[w.toNode]
            assertTrue(from != null, "wire source '${w.fromNode}' exists")
            assertTrue(to != null, "wire target '${w.toNode}' exists")
            assertTrue(from!!.index < to!!.index,
                "use before def: ${w.toNode} consumes ${w.fromNode} before it is authored")
            assertTrue(isPrefix(from.path, to.path),
                "scope violation: ${w.fromNode} (${from.path}) → ${w.toNode} (${to.path}) " +
                    "crosses outward or cousin-to-cousin")
        }
    }

    // ── (4) loud bounds, each naming its allowed range ───────────────────

    @Test
    fun boundsThrowLoudlyNamingTheBound() {
        val nine = assertFailsWith<IllegalArgumentException> {
            CouncilProgram.build(CouncilConfig(panels = List(9) { PanelSpec("p$it", "charge $it") }))
        }
        assertTrue("1..8" in (nine.message ?: ""), "panel bound named in: ${nine.message}")

        val five = assertFailsWith<IllegalArgumentException> {
            CouncilProgram.build(CouncilConfig.DEFAULT_3x5.copy(rounds = 5))
        }
        assertTrue("1..4" in (five.message ?: ""), "rounds bound named in: ${five.message}")

        val ten = assertFailsWith<IllegalArgumentException> {
            CouncilProgram.build(CouncilConfig(panels = listOf(
                PanelSpec("crowded", "charge", personas = List(10) { "persona $it" }),
            )))
        }
        assertTrue("1..9" in (ten.message ?: ""), "personas bound named in: ${ten.message}")
    }

    // ── (5) diversity: 5 distinct models per panel, personas rotate ──────

    @Test
    fun expertModelsAreDiverseWithinAndAcrossPanels() {
        val placed = flatten(CouncilProgram.build(CouncilConfig.DEFAULT_3x5))
        val experts = placed.filter { it.node.type == "council.seat" && it.node.params["role"] == "expert" }
        val byPanel = experts.groupBy { it.path.first() }
        assertEquals(3, byPanel.size, "experts sit in three panel rings")

        var panelsRidingRosterHead = 0
        for ((ring, seats) in byPanel) {
            val models = seats.sortedBy { it.node.params["seat"] }.map { it.node.params["model"]!! }
            assertEquals(5, models.toSet().size, "$ring: the 5 expert models are 5 distinct roster entries")
            assertTrue(models.all { it in CouncilProgram.DEFAULT_ROSTER }, "$ring: models come from the roster")
            if (models == CouncilProgram.DEFAULT_ROSTER.take(5)) panelsRidingRosterHead++
        }
        assertTrue(panelsRidingRosterHead < byPanel.size,
            "persona k must not ride model k in EVERY panel — assignment rotates across panels")
    }

    // ── (6) contextIds are case-qualified ────────────────────────────────

    @Test
    fun contextIdsCarryTheConveningCaseId() {
        val placed = flatten(CouncilProgram.build(CouncilConfig.DEFAULT_3x5.copy(caseId = "case-9")))
        val seats = placed.filter { it.node.type == "council.seat" }
        assertTrue(seats.isNotEmpty())
        for (s in seats) {
            val ctx = s.node.params["contextId"] ?: ""
            assertTrue(ctx.startsWith("council/case-9/"),
                "${s.node.id}: contextId '$ctx' must be council/case-9/<panel>/<seat>")
        }
        val distinct = seats.map { it.node.params["contextId"] }.toSet()
        assertEquals(seats.size, distinct.size, "every seat spends under its own contextId")
    }

    // ── (7) layout: a real grid — distinct sibling slots, nothing at 0,0 ─

    @Test
    fun layoutIsAGridWithNoOriginSquatters() {
        val program = CouncilProgram.build(CouncilConfig.DEFAULT_3x5)

        fun checkRing(ringId: String, nodes: Series<LcncNode>) {
            val slots = HashSet<Pair<Double, Double>>()
            for (i in 0 until nodes.size) {
                val n = nodes[i]
                assertTrue(n.x != 0.0 || n.y != 0.0, "${n.id} sits at the origin")
                assertTrue(slots.add(n.x to n.y), "$ringId: siblings share slot (${n.x}, ${n.y}) — ${n.id}")
                if (n.children.size > 0) checkRing(n.id, n.children)
            }
        }
        checkRing("<root>", program.nodes)
    }

    // ── (8) the judge-diet graft: evidence AND positions, exactly two ────

    @Test
    fun rulingFoldEatsExactlyEvidenceAndPositions() {
        val program = CouncilProgram.build(CouncilConfig.DEFAULT_3x5)
        val parts = ArrayList<Pair<String, String>>()
        for (i in 0 until program.wires.size) {
            val w = program.wires[i]
            if (w.toNode == "fold.ruling" && w.toPort == "parts") parts.add(w.fromNode to w.fromPort)
        }
        assertEquals(2, parts.size, "fold.ruling has exactly two part-wires")
        assertEquals(setOf("evidence" to "brief", "fold.positions" to "text"), parts.toSet(),
            "the ruling's diet is the evidence brief plus the panel positions")
    }
}
