@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.gateway.*
import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

class KanbanBoardEngine(
    private val coordinator: KanbanCoordinator = KanbanCoordinator(),
    private val cas: ContentAddressedStore = MemoryCAS(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val _cards = MutableStateFlow<List<BoardCard>>(emptyList())
    val cards: StateFlow<List<BoardCard>> = _cards.asStateFlow()

    private val _events = MutableSharedFlow<KanbanEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<KanbanEvent> = _events.asSharedFlow()

    suspend fun addCard(card: BoardCard) {
        _cards.value = _cards.value + card
        _events.emit(KanbanEvent.CardCreated(card.id, card.title))
    }

    suspend fun removeCard(cardId: String) {
        _cards.value = _cards.value.filterNot { it.id == cardId }
    }

    suspend fun loadKeys(gateway: ModelKeyGateway) {
        val keys = gateway.fetchKeys()
        for (key in keys) {
            KanbanKeyPool.instance.recordAccess(
                keyId = key.keyId,
                provider = key.provider,
                label = key.label,
                modelUrl = key.baseUrl,
            )
        }
    }

    suspend fun tick(): DispatchResult = coordinator.tick()

    suspend fun complete(cardId: String, summary: String = "") {
        _cards.value = _cards.value.map { if (it.id == cardId) it.copy(column = BoardColumn.DONE) else it }
        _events.emit(KanbanEvent.CardCompleted(cardId, summary))
    }

    suspend fun block(cardId: String, reason: String) {
        _cards.value = _cards.value.map { if (it.id == cardId) it.copy(column = BoardColumn.BLOCKED) else it }
        _events.emit(KanbanEvent.CardBlocked(cardId, reason))
    }

    suspend fun unblock(cardId: String) {
        _cards.value = _cards.value.map { if (it.id == cardId) it.copy(column = BoardColumn.TODO) else it }
        _events.emit(KanbanEvent.CardUnblocked(cardId))
    }

    suspend fun assign(cardId: String, assignee: String) {
        _cards.value = _cards.value.map { if (it.id == cardId) it.copy(assignee = assignee, column = BoardColumn.DOING) else it }
        _events.emit(KanbanEvent.CardAssigned(cardId, platformUtils.currentTimeMillis()))
    }

    suspend fun checkpoint(): String {
        val state = BoardCheckpoint(
            cards = _cards.value,
            coordinatorState = coordinator.state.value,
            timestampMs = platformUtils.currentTimeMillis(),
        )
        val bytes = state.toString().toByteArray(Charsets.UTF_8)
        val cid = cas.put(bytes)
        cas.pin(cid)
        return cid
    }

    fun startLoop(): Job = coordinator.startLoop(scope)
}

enum class BoardColumn(val ordinalValue: Int) {
    TODO(0), DOING(1), DONE(2), BLOCKED(3);

    companion object {
        fun fromName(name: String) = entries.find { it.name.equals(name, ignoreCase = true) } ?: TODO
    }

    fun logHandle() = "C$ordinalValue"
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
    fun logHandle(): String = "${column.logHandle()} P$priority A${if (assignee != null) 1 else 0}"
}

sealed interface KanbanEvent {
    data class CardCreated(val cardId: String, val title: String) : KanbanEvent
    data class CardCompleted(val cardId: String, val summary: String) : KanbanEvent
    data class CardBlocked(val cardId: String, val reason: String) : KanbanEvent
    data class CardUnblocked(val cardId: String) : KanbanEvent
    data class CardAssigned(val cardId: String, val timestampMs: Long) : KanbanEvent
}

@Serializable
data class BoardCheckpoint(
    val cards: List<BoardCard>,
    val coordinatorState: CoordinatorState,
    val timestampMs: Long,
)
