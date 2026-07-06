package borg.trikeshed.jules.client

import borg.trikeshed.ccek.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class JulesAgentState {
    CREATED,
    SESSION_ACTIVE,
    DRAINING,
    TERMINATED
}

@Serializable
data class JulesAgentHistoryEntry(
    val state: JulesAgentState,
    val timestamp: Instant,
    val details: String
)

class JulesAgent(
    val agentId: String,
    val apiKey: String,
    val client: JulesClient
) {
    private val supervisor = RealSupervisorJob("jules-agent-$agentId")

    private val _state = MutableObservable(JulesAgentState.CREATED)
    val state: Observable<JulesAgentState> get() = stateSlot

    private val _history = MutableObservable<List<JulesAgentHistoryEntry>>(
        listOf(JulesAgentHistoryEntry(JulesAgentState.CREATED, Clock.System.now(), "Agent initialized"))
    )
    val history: Observable<List<JulesAgentHistoryEntry>> get() = historySlot

    // Structured draw-through slot values
    private val stateSlot = supervisor.slot(_state)
    private val historySlot = supervisor.slot(_history)

    var sessionName: String? = null
        private set

    init {
        supervisor.open()
    }

    fun transitionTo(newState: JulesAgentState, details: String) {
        val entry = JulesAgentHistoryEntry(newState, Clock.System.now(), details)
        val currentHistory = _history.value
        _history.update(currentHistory + entry)
        _state.update(newState)

        when (newState) {
            JulesAgentState.SESSION_ACTIVE -> {
                supervisor.open()
            }
            JulesAgentState.DRAINING -> {
                supervisor.drain()
            }
            JulesAgentState.TERMINATED -> {
                supervisor.close()
            }
            else -> {}
        }
    }

    suspend fun startSession(prompt: String, title: String): JulesSession {
        check(state.value == JulesAgentState.CREATED) { "Cannot start session from state: ${state.value}" }
        val session = client.createSession(prompt = prompt, title = title)
        sessionName = session.name
        transitionTo(JulesAgentState.SESSION_ACTIVE, "Session active: name=${session.name}, id=${session.id}")
        return session
    }

    fun shutdown() {
        if (state.value != JulesAgentState.TERMINATED) {
            transitionTo(JulesAgentState.TERMINATED, "Agent shutdown requested")
        }
    }
}
