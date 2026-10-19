package borg.trikeshed.kanban

import borg.trikeshed.lib.*
import kotlin.test.*

class KanbanAbortTest {
    private val predicates = KanbanPredicateRegistry()

    private fun graph(twoAborts: Boolean = false): KanbanGraph {
        val edges = s_[
            KanbanEdge("brief-deliberate", "brief", "deliberate"),
            KanbanEdge("deliberate-settled", "deliberate", "settled"),
            KanbanEdge("brief-mistrial", "brief", "mistrial", mode = KanbanEdgeMode.ABORT),
            KanbanEdge("brief-withdrawn", "brief", "withdrawn", mode = KanbanEdgeMode.ABORT),
        ]
        return KanbanGraph("abort", s_[
            KanbanLane("brief", "Brief", 0, "counsel"),
            KanbanLane("deliberate", "Deliberate", 1, "judge"),
            KanbanLane("settled", "Settled", 2, "terminal"),
            KanbanLane("mistrial", "Mistrial", 3, "terminal"),
            KanbanLane("withdrawn", "Withdrawn", 4, "terminal"),
        ], (if (twoAborts) edges.size else edges.size - 1) j { i -> edges[i] }, s_[
            KanbanCardState("case", "council", "brief", "brief", io = mapOf("brief" to "actual input")),
        ])
    }

    private fun card(graph: KanbanGraph): KanbanCardState = graph.cards.view.single()

    private fun committed(graph: KanbanGraph, target: String?): KanbanTransitionResult.Committed {
        val current = card(graph)
        return assertIs<KanbanTransitionResult.Committed>(KanbanGraphEngine.transition(
            graph, KanbanTransitionRequest(current.id, current.revision, target), predicates))
    }

    @Test
    fun oneDeclaredAbortEscapesAfterAnOrdinaryMoveAndStopsAtTerminal() {
        val initial = graph()
        assertTrue(initial.validate(predicates).valid)
        assertEquals(1, initial.edges.view.count { it.mode == KanbanEdgeMode.ABORT })
        val entered = committed(initial, "deliberate")
        assertEquals(listOf("brief-deliberate"), entered.edgeIds)
        val before = card(entered.graph)
        assertEquals("deliberate", before.lane)
        assertEquals(1L, before.revision)
        assertTrue(entered.graph.outgoing(before.lane).size > 0)

        val aborted = committed(entered.graph, "mistrial")
        assertEquals(listOf("brief-mistrial"), aborted.edgeIds)
        val after = card(aborted.graph)
        assertEquals("mistrial", after.lane)
        assertEquals("mistrial", after.state)
        assertEquals(2L, after.revision)
        assertEquals(before.io, after.io)
        assertEquals(2, after.effects.size)
        assertEquals(before.effects[0], after.effects[0])
        assertEquals(mapOf("edge" to "brief-mistrial", "from" to "deliberate", "to" to "mistrial",
            "mode" to "ABORT", "iteration" to null), after.effects[1])
        assertTrue(aborted.lowered.isEmpty())

        val settled = committed(entered.graph, "settled")
        for (terminal in s_[aborted.graph, settled.graph].view) {
            val stopped = card(terminal)
            assertEquals(0, terminal.outgoing(stopped.lane).size)
            val refusal = assertIs<KanbanTransitionResult.Rejected>(KanbanGraphEngine.transition(
                terminal, KanbanTransitionRequest(stopped.id, stopped.revision, "mistrial"), predicates))
            assertEquals("no permitted transition from ${stopped.lane}", refusal.reason)
            assertEquals(stopped, card(terminal))
        }
    }

    @Test
    fun acyclicAbortDestinationWithAnOutgoingEdgeFailsValidation() {
        val invalid = KanbanGraph("abort-waypoint", s_[
            KanbanLane("brief", "Brief", 0, "counsel"),
            KanbanLane("waypoint", "Waypoint", 1, "worker"),
            KanbanLane("settled", "Settled", 2, "terminal"),
        ], s_[
            KanbanEdge("abort-waypoint", "brief", "waypoint", mode = KanbanEdgeMode.ABORT),
            KanbanEdge("waypoint-settled", "waypoint", "settled"),
        ], s_[KanbanCardState("case", "council", "brief", "brief")])
        val validation = invalid.validate(predicates)
        assertFalse(validation.valid, "An ABORT destination must be terminal even in an acyclic graph")
        val error = assertIs<KanbanGraphError.IncompatibleIo>(validation.errors.single())
        assertEquals("abort-waypoint", error.edge)
        assertTrue(error.reason.contains("terminal"), error.reason)
        val refusal = assertIs<KanbanTransitionResult.Rejected>(KanbanGraphEngine.transition(
            invalid, KanbanTransitionRequest("case", 0L, "waypoint"), predicates))
        assertTrue(refusal.reason.contains("terminal"), refusal.reason)
        assertEquals("brief", card(invalid).lane)
        assertEquals(0, card(invalid).effects.size)
    }

    @Test
    fun singleAbortPriorityAndExplicitTargetsApplyAtBothSources() {
        val initial = graph()
        val entered = committed(initial, "deliberate").graph
        for (current in s_[initial, entered].view) {
            val before = card(current)
            val implicit = committed(current, null)
            assertEquals(listOf("brief-mistrial"), implicit.edgeIds)
            assertEquals(before.lane, card(implicit.graph).effects[before.effects.size]["from"])
            val target = if (before.lane == "brief") "deliberate" else "settled"
            val explicit = committed(current, target)
            assertEquals(target, card(explicit.graph).lane)
            assertEquals("DIRECT", card(explicit.graph).effects[before.effects.size]["mode"])
            val missing = assertIs<KanbanTransitionResult.Rejected>(KanbanGraphEngine.transition(
                current, KanbanTransitionRequest(before.id, before.revision, "absent"), predicates))
            assertEquals("no permitted transition from ${before.lane}", missing.reason)
        }
    }

    @Test
    fun multipleAbortsRequireAnExplicitTargetAtBothSources() {
        val initial = graph(twoAborts = true)
        assertTrue(initial.validate(predicates).valid)
        val entered = committed(initial, "deliberate").graph
        for (current in s_[initial, entered].view) {
            val before = card(current)
            val ambiguous = assertIs<KanbanTransitionResult.Rejected>(KanbanGraphEngine.transition(
                current, KanbanTransitionRequest(before.id, before.revision), predicates))
            assertEquals("branch target required", ambiguous.reason)
            assertEquals(before, card(current))
            val explicit = committed(current, "withdrawn")
            assertEquals(listOf("brief-withdrawn"), explicit.edgeIds)
            assertEquals("withdrawn", card(explicit.graph).lane)
            assertEquals(before.lane, card(explicit.graph).effects[before.effects.size]["from"])
        }
    }
}
