package borg.trikeshed.jules.client

import borg.trikeshed.ccek.*
import keymux.KeyMux
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import borg.trikeshed.jules.conductor.JulesSyncConductor
import borg.trikeshed.jules.sync.SyncMessage
import borg.trikeshed.parse.confix.ConfixElement
import borg.trikeshed.parse.confix.ConfixPrimitive

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

@Serializable
data class JulesAgentStateRecord(
    val status: JulesAgentState = JulesAgentState.CREATED,
    val history: List<JulesAgentHistoryEntry> = emptyList(),
    val errorDetails: String? = null,
    val lastEventTimestamp: Instant = Clock.System.now()
)

@Serializable
sealed class JulesAgentEvent {
    abstract val timestamp: Instant

    @Serializable
    data class AgentCreated(override val timestamp: Instant) : JulesAgentEvent()

    @Serializable
    data class SessionStarted(
        val sessionName: String,
        val sessionId: String,
        override val timestamp: Instant
    ) : JulesAgentEvent()

    @Serializable
    data class StateTransitioned(
        val newState: JulesAgentState,
        val details: String,
        override val timestamp: Instant
    ) : JulesAgentEvent()

    @Serializable
    data class ExecutionFailed(
        val error: String,
        override val timestamp: Instant
    ) : JulesAgentEvent()
}

object JulesAgentFSM {
    fun reduce(event: JulesAgentEvent, prior: JulesAgentStateRecord): JulesAgentStateRecord {
        val nextStatus = when (event) {
            is JulesAgentEvent.AgentCreated -> JulesAgentState.CREATED
            is JulesAgentEvent.SessionStarted -> JulesAgentState.SESSION_ACTIVE
            is JulesAgentEvent.StateTransitioned -> event.newState
            is JulesAgentEvent.ExecutionFailed -> JulesAgentState.TERMINATED
        }
        val details = when (event) {
            is JulesAgentEvent.AgentCreated -> "Agent initialized"
            is JulesAgentEvent.SessionStarted -> "Session active: name=${event.sessionName}, id=${event.sessionId}"
            is JulesAgentEvent.StateTransitioned -> event.details
            is JulesAgentEvent.ExecutionFailed -> "Execution failed: ${event.error}"
        }
        val entry = JulesAgentHistoryEntry(nextStatus, event.timestamp, details)
        return when (event) {
            is JulesAgentEvent.AgentCreated -> prior.copy(
                status = nextStatus,
                history = prior.history + entry,
                lastEventTimestamp = event.timestamp
            )
            is JulesAgentEvent.SessionStarted -> prior.copy(
                status = nextStatus,
                history = prior.history + entry,
                lastEventTimestamp = event.timestamp
            )
            is JulesAgentEvent.StateTransitioned -> prior.copy(
                status = nextStatus,
                history = prior.history + entry,
                lastEventTimestamp = event.timestamp
            )
            is JulesAgentEvent.ExecutionFailed -> prior.copy(
                status = nextStatus,
                history = prior.history + entry,
                errorDetails = event.error,
                lastEventTimestamp = event.timestamp
            )
        }
    }
}

class JulesAgent(
    val agentId: String,
    val keyMux: KeyMux? = null,
    val apiKey: String = "",
    val client: JulesClient
) {
    val resolvedApiKey: String = runBlocking {
        val muxKey = keyMux?.get("llm.jules.key")
        if (!muxKey.isNullOrEmpty()) {
            muxKey
        } else {
            apiKey.ifEmpty { System.getenv("JULES_API_KEY") ?: "" }
        }
    }

    // Integrated Sync Conductor
    val syncConductor = JulesSyncConductor(agentId) { msg: SyncMessage ->
        // In a real application this would send via websocket or similar.
        // For now, we simulate an ack by immediately receiving it locally
        // or pushing to the underlying client's transport.
        println("Sync Conductor Outbound: ${msg.id}")
    }

    private val supervisor = RealSupervisorJob("jules-agent-$agentId")

    private val _fsmState = MutableStateFlow(
        JulesAgentStateRecord(
            history = listOf(JulesAgentHistoryEntry(JulesAgentState.CREATED, Clock.System.now(), "Agent initialized"))
        )
    )
    val fsmState: StateFlow<JulesAgentStateRecord> = _fsmState.asStateFlow()

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

    fun emit(event: JulesAgentEvent) {
        val next = JulesAgentFSM.reduce(event, _fsmState.value)
        _fsmState.value = next
        _state.update(next.status)
        _history.update(next.history)

        when (next.status) {
            JulesAgentState.SESSION_ACTIVE -> {
                supervisor.open()
                syncConductor.connect()
            }
            JulesAgentState.DRAINING -> {
                supervisor.drain()
                syncConductor.disconnect("Agent draining")
            }
            JulesAgentState.TERMINATED -> {
                supervisor.close()
            }
            else -> {}
        }
    }

    fun transitionTo(newState: JulesAgentState, details: String) {
        emit(JulesAgentEvent.StateTransitioned(newState, details, Clock.System.now()))
    }

    suspend fun startSession(prompt: String, title: String): JulesSession {
        check(state.value == JulesAgentState.CREATED) { "Cannot start session from state: ${state.value}" }
        val session = client.createSession(prompt = prompt, title = title)
        sessionName = session.name
        emit(JulesAgentEvent.SessionStarted(session.name, session.id, Clock.System.now()))

        // Initialize sync session
        syncConductor.markConnected()
        syncConductor.enqueuePayload("session-started", ConfixPrimitive(session.id), Clock.System.now().toEpochMilliseconds())

        return session
    }

    fun shutdown() {
        if (state.value != JulesAgentState.TERMINATED) {
            transitionTo(JulesAgentState.TERMINATED, "Agent shutdown requested")
            syncConductor.disconnect("Agent terminated")
        }
    }
}
