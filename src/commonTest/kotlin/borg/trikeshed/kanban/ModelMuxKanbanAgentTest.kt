package borg.trikeshed.kanban

import borg.trikeshed.job.JobCommand
import borg.trikeshed.job.JobId
import keymux.KeyMux
import kotlinx.coroutines.test.runTest
import modelmux.ModelMux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelMuxKanbanAgentTest {

    @Test
    fun agentHandlesJobCommandAndRoutesThroughMux() = runTest {
        val keyMux = KeyMux { }
        val modelMux = ModelMux(keyMux) {
            model("agent-model", caps = setOf("chat", "agentic"), baseUrl = "http://mock-agent")
        }

        val agent = ModelMuxKanbanAgent(modelMux)

        val cardId = KanbanCardId.generate()
        val jobId = JobId.of(cardId.value)

        val command = JobCommand.Move(
            jobId = jobId,
            idempotencyKey = "test-move",
            expectedRevision = 1L,
            toColumn = borg.trikeshed.job.KanbanColumnId("col-agentic")
        )

        val result = agent.handleCommand(command)
        assertTrue(result.accepted)
        assertEquals("agent-model", result.dispatchedTo)
    }
}
