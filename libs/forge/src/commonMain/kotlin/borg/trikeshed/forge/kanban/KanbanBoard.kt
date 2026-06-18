@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.*
import borg.trikeshed.forge.gateway.*
import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

/**
 * Kanban board -- the reactive center of the dispatch system.
 *
 * Bridges [KanbanCoordinator] (dispatch ticks) with [HermesAgentGateway]
 * (remote task source) and emits [KanbanEvent]s via [SharedFlow] for
 * reactor fanout to UI, metrics, and downstream consumers.
 *
 * Card state is persisted via [ContentAddressedStore] (IPFS/git CAS).
 * The board serializes to Confix JSON, puts bytes into CAS, and the
 * resulting CID becomes the board's distributed checkpoint identity.
 */
class KanbanBoardEngine(
    private val coordinator: KanbanCoordinator = KanbanCoordinator(),
    private val cas: ContentAddressedStore = MemoryCAS(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val _cards = MutableStateFlow<List<BoardCard>>(emptyList())
    val cards: StateFlow<List<BoardCard>> = _cards.asStateFlow()

    private val _events = MutableSharedFlow<KanbanEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<KanbanEvent> = _events.asSharedFlow()

    /** Load cards from a remote gateway. */
    suspend fun syncFromGateway(gateway: HermesAgentGateway) {
        val tasks = gateway.fetchTasks()
        val boardCards = tasks.map { remote ->
            BoardCard(
                id = remote.id,
                title = remote.title,
                column = when (remote.status) {
                    "done" -> BoardColumn.DONE
                    "running", "doing" -> BoardColumn.DOING
                    "blocked" -> BoardColumn.BLOCKED
                    else -> BoardColumn.TODO
                },
                assignee = remote.assignee,
                priority = remote.priority,
                dependencies = remote.dependencies,
                tags = remote.tags,
            )
        }
        _cards.value = boardCards
    }

    /** Run a dispatch tick and emit events. */
    suspend fun tick(): DispatchResult {
        val result = coordinator.tick()
        // Emit spawn events as card-moved events
        for (card in _cards.value) {
            if (card.column == BoardColumn.TODO) {
                _events.emit(KanbanEvent.CardAssigned(card.id, coordinator.state.value.tickCount.toLong()))
            }
        }
        return result
    }

    /** Complete a card and report to gateway. */
    suspend fun complete(cardId: String, gateway: HermesAgentGateway? = null, summary: String = "") {
        val updated = _cards.value.map { card ->
            if (card.id == cardId) card.copy(column = BoardColumn.DONE) else card
        }
        _cards.value = updated
        _events.emit(KanbanEvent.CardCompleted(cardId, summary))

        gateway?.reportCompletion(cardId, RemoteOutcome(
            taskId = cardId,
            success = true,
            summary = summary,
        ))
    }

    /** Checkpoint board state to CAS. Returns the CID. */
    suspend fun checkpoint(): String {
        val state = BoardCheckpoint(
            cards = _cards.value,
            coordinatorState = coordinator.state.value,
            timestampMs = platformUtils.currentTimeMillis(),
        )
        // Serialize to a deterministic byte representation
        val bytes = state.toString().toByteArray(Charsets.UTF_8)
        val cid = cas.put(bytes)
        cas.pin(cid)
        return cid
    }

    /** Start the dispatch loop. */
    fun startLoop(): Job = coordinator.startLoop(scope)

    /** Subscribe to gateway events and update board reactively. */
    fun bridgeGateway(reactor: GatewayReactor): Job = scope.launch {
        reactor.events.collect { event ->
            when (event) {
                is RemoteTaskEvent.Created -> {
                    val card = BoardCard(
                        id = event.taskId,
                        title = event.title,
                        column = BoardColumn.TODO,
                    )
                    _cards.value = _cards.value + card
                    _events.emit(KanbanEvent.CardCreated(event.taskId, event.title))
                }
                is RemoteTaskEvent.Completed -> {
                    _cards.value = _cards.value.map { card ->
                        if (card.id == event.taskId) card.copy(column = BoardColumn.DONE) else card
                    }
                    _events.emit(KanbanEvent.CardCompleted(event.taskId, event.outcome))
                }
                is RemoteTaskEvent.Blocked -> {
                    _events.emit(KanbanEvent.CardBlocked(event.taskId, event.reason))
                }
                is RemoteTaskEvent.Unblocked -> {
                    _events.emit(KanbanEvent.CardUnblocked(event.taskId))
                }
                is RemoteTaskEvent.Assigned -> {
                    _events.emit(KanbanEvent.CardAssigned(event.taskId, event.timestampMs))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Board card -- IntEnum columns for compact log handles (no varchar)
// ---------------------------------------------------------------------------

enum class BoardColumn(val ordinalValue: Int) {
    TODO(0), DOING(1), DONE(2), BLOCKED(3);

    companion object {
        fun fromName(name: String): BoardColumn =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: TODO
    }

    /** Compact log handle: C0, C1, C2, C3 */
    fun logHandle(): String = "C$ordinalValue"
}

@Serializable
data class BoardCard(
    val id: String,
    val title: String,
    val column: BoardColumn = BoardColumn.TODO,
    val assignee: String? = null,
    val priority: Int = 1,
    val dependencies: List<String> = emptyList(),
    val tags: Set<String> = emptySet(),
) {
    /** Compact log handle: C<col> P<priority> A<assignee_flag> */
    fun logHandle(): String {
        val a = if (assignee != null) 1 else 0
        return "${column.logHandle()} P$priority A$a"
    }
}

// ---------------------------------------------------------------------------
// Kanban events -- sealed interface for reactor fanout
// ---------------------------------------------------------------------------

sealed interface KanbanEvent {
    data class CardCreated(val cardId: String, val title: String) : KanbanEvent
    data class CardCompleted(val cardId: String, val summary: String) : KanbanEvent
    data class CardBlocked(val cardId: String, val reason: String) : KanbanEvent
    data class CardUnblocked(val cardId: String) : KanbanEvent
    data class CardAssigned(val cardId: String, val timestampMs: Long) : KanbanEvent
    data class CardMoved(val cardId: String, val fromColumn: String, val toColumn: String) : KanbanEvent
}

// ---------------------------------------------------------------------------
// Checkpoint for CAS persistence
// ---------------------------------------------------------------------------

@Serializable
data class BoardCheckpoint(
    val cards: List<BoardCard>,
    val coordinatorState: CoordinatorState,
    val timestampMs: Long,
)
