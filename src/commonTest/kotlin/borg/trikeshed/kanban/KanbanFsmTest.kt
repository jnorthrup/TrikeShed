package borg.trikeshed.kanban

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4 gate: the FSM speaks continue, repeat, abort.
 *
 *  - W4.3: cycles are opt-in — a back-edge is legal only when declared LOOP
 *    with a positive maxIterations; any other cycle stays ForbiddenCycle.
 *  - W4.2 guard: a LOOP edge refuses transitions past its bound, with the
 *    per-card iteration count living in KanbanCardState.io — a runaway
 *    tribunal simulation terminates by construction.
 *  - ABORT: unguarded escape to a terminal lane from any state, bypassing
 *    predicates by design and winning ambiguity resolution over branching.
 */
class KanbanFsmTest {

    private val predicates = KanbanPredicateRegistry()

    private fun cardById(graph: KanbanGraph, id: String): KanbanCardState =
        (0 until graph.cards.size).map { graph.cards[it] }.first { c -> c.id == id }

    private fun card(id: String, lane: String) =
        KanbanCardState(id = id, owner = "test", lane = lane, state = lane)

    private fun loopGraph(maxIterations: Int): KanbanGraph {
        val lanes = listOf(
            KanbanLane("argue", "Argue", 0, "legal"),
            KanbanLane("rebut", "Rebut", 1, "opposing"),
            KanbanLane("deliberate", "Deliberate", 2, "judge"),
        ).toSeries()
        val edges = listOf(
            KanbanEdge("argue-rebut", "argue", "rebut"),
            KanbanEdge("rebut-argue", "rebut", "argue", mode = KanbanEdgeMode.LOOP, maxIterations = maxIterations),
            KanbanEdge("argue-deliberate", "argue", "deliberate"),
        ).toSeries()
        return KanbanGraph("tribunal", lanes, edges, listOf(card("m1", "argue")).toSeries())
    }

    // ── W4.3: cycles opt-in ───────────────────────────────────────────────

    @Test
    fun plainBackEdgeIsStillForbidden() {
        val lanes = listOf(
            KanbanLane("a", "A", 0, "r"), KanbanLane("b", "B", 1, "r"),
        ).toSeries()
        val edges = listOf(
            KanbanEdge("a-b", "a", "b"),
            KanbanEdge("b-a", "b", "a"), // plain DIRECT back-edge
        ).toSeries()
        val v = KanbanGraph("g", lanes, edges).validate(predicates)
        assertFalse(v.valid, "undeclared cycle must remain forbidden")
        assertTrue(v.errors.any { it is KanbanGraphError.ForbiddenCycle },
            "error is ForbiddenCycle: ${v.errors}")
    }

    @Test
    fun declaredLoopEdgeLegalizesTheCycle() {
        val g = loopGraph(maxIterations = 3)
        val v = g.validate(predicates)
        assertTrue(v.valid, "LOOP-declared back-edge legalizes the argue⇄rebut cycle: ${v.errors}")
    }

    @Test
    fun loopWithoutPositiveBoundIsInvalid() {
        val lanes = listOf(
            KanbanLane("a", "A", 0, "r"), KanbanLane("b", "B", 1, "r"),
        ).toSeries()
        val edges = listOf(
            KanbanEdge("a-b", "a", "b"),
            KanbanEdge("b-a", "b", "a", mode = KanbanEdgeMode.LOOP, maxIterations = 0),
        ).toSeries()
        val v = KanbanGraph("g", lanes, edges).validate(predicates)
        assertFalse(v.valid, "LOOP with maxIterations=0 must be rejected at validation")
        assertTrue(v.errors.any { it is KanbanGraphError.IncompatibleIo },
            "${v.errors}")
    }

    // ── W4.2: iteration guard ────────────────────────────────────────────

    @Test
    fun loopTerminatesExactlyAtMaxIterations() {
        var graph = loopGraph(maxIterations = 3)
        var revision = 0L
        var iterations = 0

        // m1: argue → rebut (DIRECT), then rebut → argue (LOOP) repeatedly.
        fun advance(target: String?): KanbanTransitionResult {
            val r = KanbanGraphEngine.transition(graph, KanbanTransitionRequest("m1", revision, target), predicates)
            if (r is KanbanTransitionResult.Committed) {
                graph = r.graph
                revision++
                iterations++
            }
            return r
        }

        // argue → rebut (DIRECT #1); branch targets explicit since `argue`
        // legitimately fans out to both rebut and deliberate.
        assertTrue(advance("rebut") is KanbanTransitionResult.Committed)
        // Now in `rebut`. One full cycle = rebut→argue (the LOOP edge) followed
        // by argue→rebut (DIRECT). Three LOOP traversals hit the bound exactly.
        repeat(3) { i ->
            val r = advance("argue")
            assertTrue(r is KanbanTransitionResult.Committed, "iteration ${i + 1} (rebut→argue LOOP) must commit")
            if (i < 2) {
                // Only bounce back while iterations remain; after the 3rd we stop
                // in argue and demonstrate exhaustion from there.
                assertTrue(advance("rebut") is KanbanTransitionResult.Committed, "cycle ${i + 1}: argue→rebut must commit")
            }
        }
        assertEquals(3, iterationsOf(graph, "m1", "rebut-argue"), "io records every traversal")
    }

    @Test
    fun loopPastBoundIsRefusedWhenNextAttempted() {
        var graph = loopGraph(maxIterations = 2)
        var revision = 0L
        fun advance(target: String?): KanbanTransitionResult {
            val r = KanbanGraphEngine.transition(graph, KanbanTransitionRequest("m1", revision, target), predicates)
            if (r is KanbanTransitionResult.Committed) { graph = r.graph; revision++ }
            return r
        }
        // argue→rebut, then LOOP twice: now rebut→argue is exhausted.
        assertTrue(advance("rebut") is KanbanTransitionResult.Committed)
        assertTrue(advance("argue") is KanbanTransitionResult.Committed)  // LOOP #1
        assertTrue(advance("rebut") is KanbanTransitionResult.Committed)
        assertTrue(advance("argue") is KanbanTransitionResult.Committed)  // LOOP #2
        assertEquals(2, iterationsOf(graph, "m1", "rebut-argue"))
        // Back in argue; walk to rebut and try the loop a third time → refused.
        assertTrue(advance("rebut") is KanbanTransitionResult.Committed)
        val refused = advance("argue")
        assertTrue(refused is KanbanTransitionResult.Rejected,
            "past the bound the engine refuses: $refused")
        assertTrue((refused as KanbanTransitionResult.Rejected).reason.contains("exhausted"))
        assertEquals("rebut", cardById(graph, "m1").lane, "no phantom move on refusal")
    }

    @Test
    fun otherCardsIterateIndependently() {
        var graph = loopGraph(maxIterations = 2).let { g ->
            g.copy(cards = listOf(card("m1", "argue"), card("m2", "argue")).toSeries())
        }
        var rev1 = 0L
        var rev2 = 0L
        fun advance(cardId: String, revision: Long, target: String?): Pair<KanbanTransitionResult, Long> {
            val r = KanbanGraphEngine.transition(graph, KanbanTransitionRequest(cardId, revision, target), predicates)
            if (r is KanbanTransitionResult.Committed) graph = r.graph
            return r to if (r is KanbanTransitionResult.Committed) revision + 1 else revision
        }
        // Card 1 burns BOTH of its loop iterations. Each LOOP traversal needs the
        // card back in argue first: argue→rebut (DIRECT), rebut→argue (LOOP).
        var r = advance("m1", rev1, "rebut"); rev1 = r.second          // argue→rebut
        r = advance("m1", rev1, "argue"); rev1 = r.second              // LOOP #1
        assertTrue(r.first is KanbanTransitionResult.Committed)
        r = advance("m1", rev1, "rebut"); rev1 = r.second              // argue→rebut
        r = advance("m1", rev1, "argue"); rev1 = r.second              // LOOP #2
        assertTrue(r.first is KanbanTransitionResult.Committed)
        r = advance("m1", rev1, "rebut"); rev1 = r.second              // argue→rebut
        r = advance("m1", rev1, "argue"); rev1 = r.second              // LOOP #3 → refused
        assertTrue(r.first is KanbanTransitionResult.Rejected, "card 1 exhausted")
        // Card 2 still has its full allowance — counts never cross cards.
        r = advance("m2", rev2, "rebut"); rev2 = r.second
        assertTrue(r.first is KanbanTransitionResult.Committed, "card 2 unaffected by card 1's exhaustion")
        r = advance("m2", rev2, "argue"); rev2 = r.second               // LOOP #1 for m2
        assertTrue(r.first is KanbanTransitionResult.Committed)
    }

    @Test
    fun loopEffectsCarryIterationNumber() {
        var graph = loopGraph(maxIterations = 5)
        var revision = 0L
        // argue → rebut (explicit target: `argue` fans out to rebut + deliberate)
        KanbanGraphEngine.transition(graph, KanbanTransitionRequest("m1", revision, "rebut"), predicates).let {
            graph = (it as KanbanTransitionResult.Committed).graph; revision++
        }
        val c0b = KanbanGraphEngine.transition(graph, KanbanTransitionRequest("m1", revision, "argue"), predicates) as KanbanTransitionResult.Committed
        graph = c0b.graph; revision++                                  // LOOP #1 (rebut→argue)
        val c1 = KanbanGraphEngine.transition(graph, KanbanTransitionRequest("m1", revision, "rebut"), predicates) as KanbanTransitionResult.Committed
        graph = c1.graph; revision++                                   // argue→rebut
        val c2 = KanbanGraphEngine.transition(graph, KanbanTransitionRequest("m1", revision, "argue"), predicates) as KanbanTransitionResult.Committed
        graph = c2.graph                                               // LOOP #2 (rebut→argue)

        val m1 = cardById(graph, "m1")
        val effects: List<Map<String, Any?>> = (0 until m1.effects.size).map { i -> m1.effects[i] }
        @Suppress("UNCHECKED_CAST")
        val loopEffect = effects.last { e -> (e["mode"] as? String) == "LOOP" }
        assertEquals(2, loopEffect["iteration"], "second traversal of the LOOP edge records iteration 2")
    }

    // ── ABORT ─────────────────────────────────────────────────────────────

    private fun abortGraph(): KanbanGraph {
        val lanes = listOf(
            KanbanLane("brief", "Brief", 0, "legal"),
            KanbanLane("deliberate", "Deliberate", 1, "judge"),
            KanbanLane("mistrial", "Mistrial", 2, "terminal"),
        ).toSeries()
        val edges = listOf(
            KanbanEdge("brief-deliberate", "brief", "deliberate"),
            KanbanEdge("brief-mistrial", "brief", "mistrial", mode = KanbanEdgeMode.ABORT),
        ).toSeries()
        return KanbanGraph("t", lanes, edges, listOf(card("case", "brief")).toSeries())
    }

    @Test
    fun abortBypassesPredicatesAndAmbiguity() {
        // Two outgoing candidates (deliberate + mistrial/ABORT) with NO requested
        // target would normally reject as "branch target required"; ABORT wins.
        val result = KanbanGraphEngine.transition(abortGraph(), KanbanTransitionRequest("case", 0), predicates)
        assertTrue(result is KanbanTransitionResult.Committed, "ABORT resolves the ambiguity: $result")
        assertEquals("mistrial", cardById((result as KanbanTransitionResult.Committed).graph, "case").lane)
    }

    @Test
    fun abortWithExplicitOtherTargetStillHonoursRequest() {
        // Explicit target ≠ abort target ⇒ normal branching rules apply.
        val result = KanbanGraphEngine.transition(abortGraph(), KanbanTransitionRequest("case", 0, "deliberate"), predicates)
        assertTrue(result is KanbanTransitionResult.Committed)
        assertEquals("deliberate", cardById((result as KanbanTransitionResult.Committed).graph, "case").lane)
    }

    private fun iterationsOf(graph: KanbanGraph, cardId: String, edgeId: String): Int =
        (cardById(graph, cardId).io["loop.iterations.$edgeId"] as? Number)?.toInt() ?: 0
}
