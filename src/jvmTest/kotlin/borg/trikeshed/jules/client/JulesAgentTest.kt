package borg.trikeshed.jules.client

import borg.trikeshed.ccek.*
import borg.trikeshed.htx.*
import borg.trikeshed.lib.ByteSeries
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.coroutines.coroutineContext

class JulesAgentTest {

    class FakeHtxRouteService(val name: String, val id: String) : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val response = HtxResponse(
                status = 200,
                body = ByteSeries("""{"name": "$name", "id": "$id"}""".encodeToByteArray())
            )
            val responded = state.copy(
                lifecycle = HtxExchangeLifecycle.RESPONDED,
                request = request,
                response = response
            )
            return HtxExchangeResult(responded)
        }
    }

    @Test
    fun agentLifecycle_stateAndHistoryTransitions() = runTest {
        val fakeService = FakeHtxRouteService("sessions/s123", "s123")
        val htxElement = openHtxElement(routeService = fakeService)
        val context = coroutineContext + htxElement
        val client = JulesClient(context, "my-key")

        val agent = JulesAgent(agentId = "agent-42", apiKey = "my-key", client = client)

        assertEquals(JulesAgentState.CREATED, agent.state.value)
        assertEquals(1, agent.history.value.size)
        assertEquals(JulesAgentState.CREATED, agent.history.value[0].state)

        // Observe changes
        val stateChanges = mutableListOf<JulesAgentState>()
        val historyChanges = mutableListOf<List<JulesAgentHistoryEntry>>()

        val stateToken = agent.state.observe { stateChanges.add(it) }
        val historyToken = agent.history.observe { historyChanges.add(it) }

        // Initial observe fires immediately with initial value
        assertEquals(listOf(JulesAgentState.CREATED), stateChanges)
        assertEquals(1, historyChanges.size)

        // Start session
        val session = agent.startSession(prompt = "auto-migrate keymux", title = "migration-job")
        assertEquals("sessions/s123", session.name)
        assertEquals("sessions/s123", agent.sessionName)

        // Draw-through should trigger immediate updates
        assertEquals(JulesAgentState.SESSION_ACTIVE, agent.state.value)
        assertEquals(2, agent.history.value.size)
        assertEquals(JulesAgentState.SESSION_ACTIVE, agent.history.value[1].state)
        assertTrue(agent.history.value[1].details.contains("Session active"))

        // Observers fired
        assertEquals(listOf(JulesAgentState.CREATED, JulesAgentState.SESSION_ACTIVE), stateChanges)
        assertEquals(2, historyChanges.size)

        // Transition to Draining
        agent.transitionTo(JulesAgentState.DRAINING, "Finishing work on session")
        assertEquals(JulesAgentState.DRAINING, agent.state.value)
        assertEquals(3, agent.history.value.size)
        assertEquals(JulesAgentState.DRAINING, agent.history.value[2].state)

        // Transition to Terminated
        agent.shutdown()
        assertEquals(JulesAgentState.TERMINATED, agent.state.value)
        assertEquals(4, agent.history.value.size)
        assertEquals(JulesAgentState.TERMINATED, agent.history.value[3].state)

        stateToken.cancel()
        historyToken.cancel()
        htxElement.close()
    }
}
