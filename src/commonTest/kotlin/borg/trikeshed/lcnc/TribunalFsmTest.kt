package borg.trikeshed.lcnc

import borg.trikeshed.kanban.KanbanCardState
import borg.trikeshed.kanban.KanbanEdgeMode
import borg.trikeshed.kanban.KanbanGraph
import borg.trikeshed.kanban.KanbanGraphEngine
import borg.trikeshed.kanban.KanbanGraphValidation
import borg.trikeshed.kanban.KanbanPredicateRegistry
import borg.trikeshed.kanban.KanbanTransitionRequest
import borg.trikeshed.kanban.KanbanTransitionResult
import borg.trikeshed.kanban.validate
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 4 gate, W6.5: the tribunal IS the FSM. This test does not build a
 * synthetic graph — it loads the PRESET's own kanban (round-tripped through
 * Confix, i.e. the document the daemon actually ships) and runs it to
 * verdict: argue ⇄ rebut bounded at 3, the JOIN record to deliberate, and
 * the ABORT escape to mistrial.
 */
class TribunalFsmTest {

    private val predicates = TribunalPredicates.registry()

    private fun presetKanban(): KanbanGraph {
        val json = LcncPresets.all()["preset-tribunal"] ?: error("preset-tribunal missing")
        val program = LcncProgramConfix.fromJson("preset-tribunal", json)
        val kb = program.kanban ?: error("preset-tribunal must carry its kanban")
        return kb
    }

    @Test
    fun presetKanbanValidates() {
        val v = presetKanban().validate(predicates)
        assertIs<KanbanGraphValidation>(v)
        assertTrue(v.valid, "the shipped tribunal kanban must validate: ${v.errors}")
    }

    private fun seed(graph: KanbanGraph, lane: String): KanbanGraph =
        graph.copy(cards = listOf(KanbanCardState("case", "test", lane, lane)).toSeries())

    @Test
    fun fullTrialRunsToDeliberateWithBoundedLoop() {
        val g0 = seed(presetKanban(), "brief")
        var graph = g0
        var revision = 0L
        fun advance(target: String?): KanbanTransitionResult {
            val r = KanbanGraphEngine.transition(graph, KanbanTransitionRequest("case", revision, target), predicates)
            if (r is KanbanTransitionResult.Committed) { graph = r.graph; revision++ }
            return r
        }
        val card = { (0 until graph.cards.size).map { graph.cards[it] }.first { it.id == "case" } }

        // brief → argue
        assertIs<KanbanTransitionResult.Committed>(advance("argue"))
        // argue → rebut, then the LOOP edge back — three traversals, then refused.
        repeat(3) { i ->
            assertIs<KanbanTransitionResult.Committed>(advance("rebut"), "rebut $i")
            assertIs<KanbanTransitionResult.Committed>(advance("argue"), "loop-back $i")
        }
        // Card is at "argue" with used=3; step to rebut, then the 4th traversal fails.
        assertIs<KanbanTransitionResult.Committed>(advance("rebut"), "rebut after loops")
        val exhausted = advance("argue")
        assertIs<KanbanTransitionResult.Rejected>(exhausted, "4th loop traversal must be refused")
        assertTrue(exhausted.reason.contains("exhausted"), exhausted.reason)
        assertEquals("rebut", card().lane, "no phantom move on refusal")
        // The record is full: JOIN the two branches into deliberation.
        assertIs<KanbanTransitionResult.Committed>(advance("deliberate"))
        assertEquals("deliberate", card().lane)
    }

    @Test
    fun abortIsTheMistrialEscape() {
        // From argue, the unguarded ABORT edge resolves the ambiguity in its
        // own favor even alongside the other candidates.
        val g = seed(presetKanban(), "argue")
        val r = KanbanGraphEngine.transition(g, KanbanTransitionRequest("case", 0L), predicates)
        assertIs<KanbanTransitionResult.Committed>(r, "ABORT wins the ambiguity")
        val c = (0 until r.graph.cards.size).map { r.graph.cards[it] }.first { it.id == "case" }
        assertEquals("mistrial", c.lane)
    }

    @Test
    fun loopEdgeIsDeclaredBounded() {
        val g = presetKanban()
        val loop = (0 until g.edges.size).map { g.edges[it] }
            .first { it.id == "rebut-argue" }
        assertEquals(KanbanEdgeMode.LOOP, loop.mode)
        assertEquals(3, loop.maxIterations)
    }

    @Test
    fun clarificationLoopIsDeclaredGuardedAndBounded() {
        val g = presetKanban()
        val loop = (0 until g.edges.size).map { g.edges[it] }
            .first { it.id == "deliberate-clarify" }
        assertEquals("deliberate", loop.from)
        assertEquals("argue", loop.to)
        assertEquals(KanbanEdgeMode.LOOP, loop.mode)
        assertEquals(1, loop.maxIterations)
        assertEquals(TribunalPredicates.NEEDS_CLARIFICATION, loop.condition?.predicate)
    }

    @Test
    fun judgeClarificationLoopIsGuardedAndBounded() {
        // Without the judge's flag, the guard refuses — deliberate stays terminal
        // exactly as it did before this edge existed.
        val silent = seed(presetKanban(), "deliberate")
        val refused = KanbanGraphEngine.transition(silent, KanbanTransitionRequest("case", 0L, "argue"), predicates)
        assertIs<KanbanTransitionResult.Rejected>(refused, "no clarification flag: guard must refuse")

        // With the flag, the judge's loop-back is legal and lands the record
        // back at argue for a fresh round.
        val flagged = presetKanban().copy(
            cards = listOf(KanbanCardState("case", "test", "deliberate", "deliberate", io = mapOf("clarification" to true))).toSeries(),
        )
        val committed = KanbanGraphEngine.transition(flagged, KanbanTransitionRequest("case", 0L, "argue"), predicates)
        assertIs<KanbanTransitionResult.Committed>(committed, "clarification flag set: loop-back must commit")
        val moved = (0 until committed.graph.cards.size).map { committed.graph.cards[it] }.first { it.id == "case" }
        assertEquals("argue", moved.lane)

        // A second attempt (as if the judge asked twice) is bounded at 1 —
        // same iteration-exhaustion mechanism as the counsel-level loop.
        val usedOnce = presetKanban().copy(
            cards = listOf(KanbanCardState(
                "case", "test", "deliberate", "deliberate",
                io = mapOf("clarification" to true, "loop.iterations.deliberate-clarify" to 1),
            )).toSeries(),
        )
        val exhausted = KanbanGraphEngine.transition(usedOnce, KanbanTransitionRequest("case", 0L, "argue"), predicates)
        assertIs<KanbanTransitionResult.Rejected>(exhausted, "second clarification round must be refused")
        assertTrue(exhausted.reason.contains("exhausted"), exhausted.reason)
    }
}
