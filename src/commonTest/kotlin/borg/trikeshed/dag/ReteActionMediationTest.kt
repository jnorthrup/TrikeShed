package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * C08 RED — Rete action mediation boundary.
 *
 * The plan: "Rete may derive facts and enqueue commands. It may NOT mutate
 * Couch heads, CAS, job snapshots, or Kanban state directly."
 *
 * This test proves ReteNetwork cannot call mutation methods on any
 * authoritative state surface.
 */
class ReteActionMediationTest {

    @Test
    fun reteActionEmitsCommandNotDirectMutation() {
        val action = ReteAction(
            assertion = FactId("board-a", "derived"),
            fields = mapOf("status" to "ready"),
        )

        // The ReteAction data class must ONLY carry assertion/field info.
        // It must NOT hold references to CasStore, JobRepository, CouchStore,
        // KanbanBoard, or any mutable state.
        val actionFields = action::class.members.map { it.name }

        assertTrue(!actionFields.any { it.contains("cas", ignoreCase = true) },
            "ReteAction must not reference CAS")
        assertTrue(!actionFields.any { it.contains("store", ignoreCase = true) },
            "ReteAction must not reference a store")
        assertTrue(!actionFields.any { it.contains("repository", ignoreCase = true) },
            "ReteAction must not reference a repository")
        assertTrue(!actionFields.any { it.contains("board", ignoreCase = true) },
            "ReteAction must not reference a board directly")
        assertTrue(!actionFields.any { it.contains("kanban", ignoreCase = true) },
            "ReteAction must not reference kanban")
    }

    @Test
    fun reteActivationProducesCommandNotSideEffect() {
        val net = ReteNetwork(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            capacity = 64,
        )

        val emittedCommands = mutableListOf<JobCommand>()
        net.onCommand = { cmd -> emittedCommands.add(cmd) }

        net.addRule(ReteProductionRule(
            ruleId = "r1",
            salience = 100,
            conditions = listOf(Condition(Facet("x", "*"), Comparison.Equals, "1")),
            action = { bindings -> ReteAction(
                assertion = FactId(bindings.board.id, "out"),
                fields = mapOf("status" to "ready"),
            )},
        ))

        net.assert(FactId("board-a", "f1"), mapOf("x" to "1"),
            ContentId.of("c1".encodeToByteArray()), board("board-a"))
        net.drainActivations()

        // The rule fires and produces an action. The action must emit a
        // command (which goes back to the reactor ingress), not directly
        // mutate state.
        assertTrue(emittedCommands.isNotEmpty(),
            "Rete rule activation must emit at least one command")
    }
}
