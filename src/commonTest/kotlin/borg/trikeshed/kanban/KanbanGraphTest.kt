package borg.trikeshed.kanban

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KanbanGraphTest {
    private fun graph() = KanbanGraph(
        "b",
        listOf(
            KanbanLane("a", "A", 0, "intake", outputs = mapOf("x" to "json")),
            KanbanLane("b", "B", 1, "worker", inputs = mapOf("x" to "json"), outputs = mapOf("x" to "json")),
            KanbanLane("c", "C", 2, "review", inputs = mapOf("x" to "json")),
        ).toSeries(),
        listOf(
            KanbanEdge("ab", "a", "b"),
            KanbanEdge("bc", "b", "c", KanbanCondition("approved")),
        ).toSeries(),
        listOf(KanbanCardState("card", "jim", "a", "a")).toSeries(),
    )

    @Test fun validationFindsUnresolvedPredicateAndMissingEndpoint() {
        val bad = graph().copy(edges = listOf(KanbanEdge("bad", "a", "gone", KanbanCondition("nope"))).toSeries())
        val result = bad.validate()
        assertFalse(result.valid)
        assertTrue(result.errors.any { it is KanbanGraphError.MissingEndpoint })
        assertTrue(result.errors.any { it is KanbanGraphError.UnresolvedPredicate })
    }

    @Test fun conditionalBranchCommitsOnlyWhenPredicateAllows() {
        val predicates = KanbanPredicateRegistry().plus("approved", KanbanPredicate { card, _ -> card.io["approved"] == true })
        val rejected = KanbanGraphEngine.transition(graph().copy(cards = listOf(KanbanCardState("card", "jim", "b", "b")).toSeries()), KanbanTransitionRequest("card", 0, "c"), predicates)
        assertTrue(rejected is KanbanTransitionResult.Rejected)
        val allowedGraph = graph().copy(cards = listOf(KanbanCardState("card", "jim", "b", "b", io = mapOf("approved" to true))).toSeries())
        val committed = KanbanGraphEngine.transition(allowedGraph, KanbanTransitionRequest("card", 0, "c"), predicates)
        assertTrue(committed is KanbanTransitionResult.Committed)
        assertEquals("c", (committed as KanbanTransitionResult.Committed).graph.cards[0].lane)
    }

    @Test fun graphPersistsLaneOrderConditionsOwnerStateIoAndEffects() {
        val predicates = KanbanPredicateRegistry().plus("approved", KanbanPredicate { _, _ -> true })
        val committed = KanbanGraphEngine.transition(graph().copy(cards = listOf(KanbanCardState("card", "jim", "b", "b", io = mapOf("approved" to true))).toSeries()), KanbanTransitionRequest("card", 0, "c"), predicates) as KanbanTransitionResult.Committed
        val reloaded = KanbanGraphConfix.fromJson(KanbanGraphConfix.toJson(committed.graph))
        assertEquals(listOf("a", "b", "c"), reloaded.lanes.toList().sortedBy { it.order }.map { it.id })
        assertEquals("jim", reloaded.cards[0].owner)
        assertEquals("c", reloaded.cards[0].state)
        assertEquals("bc", reloaded.cards[0].effects[0]["edge"])
    }
}
