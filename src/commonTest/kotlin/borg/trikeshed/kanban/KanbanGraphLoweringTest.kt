package borg.trikeshed.kanban

import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 4 gate, W4.4: FANOUT/JOIN lower to real Submit commands, not
 * decorative name-copying. FANOUT → N Submits (one per branch); JOIN → one
 * Submit whose dependencies are those branch jobIds. Idempotency keys
 * follow "$jobId#$ruleId#$rev".
 */
class KanbanGraphLoweringTest {

    private val predicates = KanbanPredicateRegistry()

    private fun fanOutGraph(): KanbanGraph {
        // brief → [FANOUT group="teams"] → legal, opposing → [JOIN group="teams"] → deliberate
        val lanes = listOf(
            KanbanLane("brief", "Brief", 0, "intake", outputs = mapOf("card" to "work")),
            KanbanLane("legal", "Legal", 1, "counsel", inputs = mapOf("card" to "work"), outputs = mapOf("result" to "result")),
            KanbanLane("opposing", "Opposing", 2, "counsel", inputs = mapOf("card" to "work"), outputs = mapOf("result" to "result")),
            KanbanLane("deliberate", "Deliberate", 3, "judge", inputs = mapOf("result" to "result")),
        )
        val edges = listOf(
            KanbanEdge("brief-legal", "brief", "legal", mode = KanbanEdgeMode.FANOUT, group = "teams", requiredBranches = 2),
            KanbanEdge("brief-opposing", "brief", "opposing", mode = KanbanEdgeMode.FANOUT, group = "teams", requiredBranches = 2),
            KanbanEdge("legal-deliberate", "legal", "deliberate", mode = KanbanEdgeMode.JOIN, group = "teams", requiredBranches = 2),
            KanbanEdge("opposing-deliberate", "opposing", "deliberate", mode = KanbanEdgeMode.JOIN, group = "teams", requiredBranches = 2),
        )
        return KanbanGraph("tribunal", lanes.toSeries(), edges.toSeries())
    }

    @Test
    fun fanoutLowersToNSubmits() {
        val g = fanOutGraph()
        val card = KanbanCardState("m1", "judge", "brief", "brief", revision = 0L)
        val graph = g.copy(cards = listOf(card).toSeries())
        val result = KanbanGraphEngine.transition(graph, KanbanTransitionRequest("m1", 0L, "legal"), predicates)
        assertIs<KanbanTransitionResult.Committed>(result)
        // FANOUT lowers to N Submits — one per branch edge in group "teams".
        assertEquals(2, result.lowered.size, "FANOUT emits one Submit per branch")
        for (cmd in result.lowered) {
            assertEquals("submit", cmd.type)
            assertTrue(cmd.idempotencyKey.startsWith("m1#"), "idempotency key follows jobId#ruleId#rev: ${cmd.idempotencyKey}")
            assertTrue(cmd.jobId.contains("#teams#"), "branch jobId carries group: ${cmd.jobId}")
        }
    }

    @Test
    fun joinLowersToOneSubmitWithDependencies() {
        val g = fanOutGraph()
        // Card is already in "legal" lane — advancing to deliberate via JOIN.
        val card = KanbanCardState("m1", "judge", "legal", "legal", revision = 0L)
        val graph = g.copy(cards = listOf(card).toSeries())
        val result = KanbanGraphEngine.transition(graph, KanbanTransitionRequest("m1", 0L, "deliberate"), predicates)
        assertIs<KanbanTransitionResult.Committed>(result)
        // JOIN lowers to one Submit whose dependencies are the branch jobIds.
        assertEquals(1, result.lowered.size, "JOIN emits one Submit")
        val join = result.lowered.first()
        assertEquals("submit", join.type)
        assertTrue(join.dependencies.size == 2, "JOIN dependencies list every branch: ${join.dependencies}")
        assertTrue(join.jobId.contains("#teams#join"), "join jobId carries group+join marker: ${join.jobId}")
    }

    @Test
    fun directEdgeLowersNoCommands() {
        val directGraph = KanbanGraph(
            "simple",
            listOf(KanbanLane("a", "A", 0, "x"), KanbanLane("b", "B", 1, "x")).toSeries(),
            listOf(KanbanEdge("a-b", "a", "b", mode = KanbanEdgeMode.DIRECT)).toSeries(),
            listOf(KanbanCardState("c1", "u", "a", "a", revision = 0L)).toSeries(),
        )
        val result = KanbanGraphEngine.transition(directGraph, KanbanTransitionRequest("c1", 0L, "b"), predicates)
        assertIs<KanbanTransitionResult.Committed>(result)
        assertEquals(0, result.lowered.size, "DIRECT lowers nothing")
    }
}
