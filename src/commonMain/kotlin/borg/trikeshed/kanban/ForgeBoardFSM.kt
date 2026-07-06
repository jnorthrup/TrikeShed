package borg.trikeshed.kanban

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Forge-native Kanban FSM — board/card lifecycle events.
 *
 * Separate from the reactor's KanbanFSM (keymux/modelmux/taxonomy).
 * This FSM is the source of truth for all KanbanBoard mutations observable
 * by the UI. The reactor may bridge into this stream via
 * [ForgeBoardFSM.emit].
 */
@OptIn(ExperimentalAtomicApi::class)
object ForgeBoardFSM {

    private data class ColumnSequenceKey(
        val boardId: KanbanBoardId,
        val columnId: KanbanColumnId,
    )

    // ─── Event bus ─────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<ForgeBoardEvent>(
        replay = 128,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ForgeBoardEvent> = _events.asSharedFlow()

    // ─── Board state ────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(ForgeBoardState())
    private val emitGuard = AtomicBoolean(false)
    private val columnSequences = mutableMapOf<ColumnSequenceKey, AtomicLong>()
    val state: StateFlow<ForgeBoardState> = _state.asStateFlow()

    fun current(): ForgeBoardState = _state.value

    // ─── Emit / reduce ──────────────────────────────────────────────────────

    /** Emit an event and immediately fold it into state. Best-effort (no block). */
    fun emit(event: ForgeBoardEvent) {
        _events.tryEmit(event)
        withEmitLock {
            _state.value = reduce(event, _state.value)
        }
    }

    private inline fun <T> withEmitLock(block: () -> T): T {
        while (!emitGuard.compareAndSet(false, true)) {
            // Spin: these transitions are tiny and in-memory.
        }
        return try {
            block()
        } finally {
            emitGuard.store(false)
        }
    }

    /** Pure reducer; side-effect free. */
    fun reduce(event: ForgeBoardEvent, prior: ForgeBoardState = current()): ForgeBoardState =
        when (event) {
            is ForgeBoardEvent.BoardLoaded -> prior.copy(
                boards = prior.boards + (event.board.id to event.board),
                activeBoardId = prior.activeBoardId ?: event.board.id,
                lastEventKind = "BoardLoaded",
                lastEventMs = event.timestampMs,
            ).also { seedColumnSequences(event.board) }

            is ForgeBoardEvent.BoardSelected -> prior.copy(
                activeBoardId = event.boardId,
                lastEventKind = "BoardSelected",
                lastEventMs = event.timestampMs,
            )

            is ForgeBoardEvent.CardMoved -> {
                val board = prior.boards[event.boardId] ?: return prior
                val newBoard = board.moveCard(event.cardId, event.toColumnId)
                prior.copy(
                    boards = prior.boards + (event.boardId to newBoard),
                    lastEventKind = "CardMoved",
                    lastEventMs = event.timestampMs,
                )
            }

            is ForgeBoardEvent.CardCreated -> {
                val board = prior.boards[event.boardId] ?: return prior
                val newCard = KanbanCard(
                    id = event.cardId,
                    title = event.title,
                    description = event.description,
                    columnId = event.columnId,
                    priority = event.priority,
                    assignee = event.assignee,
                    order = nextCardOrder(board, event.boardId, event.columnId),
                )
                val newBoard = board.copy(cards = board.cards + newCard)
                prior.copy(
                    boards = prior.boards + (event.boardId to newBoard),
                    lastEventKind = "CardCreated",
                    lastEventMs = event.timestampMs,
                )
            }

            is ForgeBoardEvent.CardDeleted -> {
                val board = prior.boards[event.boardId] ?: return prior
                val newBoard = board.deleteCard(event.cardId)
                prior.copy(
                    boards = prior.boards + (event.boardId to newBoard),
                    lastEventKind = "CardDeleted",
                    lastEventMs = event.timestampMs,
                )
            }

            is ForgeBoardEvent.CardUpdated -> {
                val board = prior.boards[event.boardId] ?: return prior
                val newBoard = board.updateCard(event.card)
                prior.copy(
                    boards = prior.boards + (event.boardId to newBoard),
                    lastEventKind = "CardUpdated",
                    lastEventMs = event.timestampMs,
                )
            }

            is ForgeBoardEvent.DragStarted -> prior.copy(
                dragState = DragState(
                    boardId = event.boardId,
                    cardId = event.cardId,
                    fromColumnId = event.fromColumnId,
                    overColumnId = event.fromColumnId,
                ),
                lastEventKind = "DragStarted",
                lastEventMs = event.timestampMs,
            )

            is ForgeBoardEvent.DragOver -> prior.copy(
                dragState = prior.dragState?.copy(overColumnId = event.overColumnId),
                lastEventKind = "DragOver",
                lastEventMs = event.timestampMs,
            )

            is ForgeBoardEvent.DragDropped -> {
                val drag = prior.dragState ?: return prior
                val board = prior.boards[drag.boardId] ?: return prior
                val newBoard = board.moveCard(drag.cardId, drag.overColumnId ?: drag.fromColumnId)
                prior.copy(
                    boards = prior.boards + (drag.boardId to newBoard),
                    dragState = null,
                    lastEventKind = "DragDropped",
                    lastEventMs = event.timestampMs,
                )
            }

            is ForgeBoardEvent.DragCancelled -> prior.copy(
                dragState = null,
                lastEventKind = "DragCancelled",
                lastEventMs = event.timestampMs,
            )
        }

    /** Load a default board. Convenience for bootstrap. */
    fun loadDefault() {
        val backlog = KanbanColumnId("col-backlog")
        val inprog  = KanbanColumnId("col-inprogress")
        val review  = KanbanColumnId("col-review")
        val done    = KanbanColumnId("col-done")
        val board = KanbanBoard(
            id = KanbanBoardId("board-default"),
            name = "Forge Board",
            columns = listOf(
                KanbanColumn(backlog, "Backlog",     0),
                KanbanColumn(inprog,  "In Progress", 1, wipLimit = 3),
                KanbanColumn(review,  "Review",      2),
                KanbanColumn(done,    "Done",        3),
            ),
            cards = listOf(
                KanbanCard(KanbanCardId("c1"), "Setup CI pipeline",         columnId = backlog, priority = CardPriority.HIGH),
                KanbanCard(KanbanCardId("c2"), "Add user authentication",   columnId = backlog),
                KanbanCard(KanbanCardId("c3"), "Implement API gateway",     columnId = inprog,  priority = CardPriority.CRITICAL),
                KanbanCard(KanbanCardId("c4"), "Code review: HTX client",   columnId = review,  priority = CardPriority.HIGH),
                KanbanCard(KanbanCardId("c5"), "Initial commit",            columnId = done,    priority = CardPriority.LOW),
                KanbanCard(KanbanCardId("c6"), "Fix login bug",             columnId = backlog, priority = CardPriority.HIGH),
            ),
        )
        emit(ForgeBoardEvent.BoardLoaded(board, nowMs()))
    }

    /** For test isolation only. */
    fun reset() {
        withEmitLock {
            _state.value = ForgeBoardState()
            columnSequences.clear()
        }
    }

    private fun seedColumnSequences(board: KanbanBoard) {
        board.columns.forEach { column ->
            val nextOrder = (board.cardsInColumn(column.id).maxOfOrNull { it.order } ?: -1) + 1
            columnSequences[ColumnSequenceKey(board.id, column.id)] = AtomicLong(nextOrder.toLong())
        }
    }

    private fun nextCardOrder(board: KanbanBoard, boardId: KanbanBoardId, columnId: KanbanColumnId): Int {
        val key = ColumnSequenceKey(boardId, columnId)
        val sequence = columnSequences.getOrPut(key) {
            val nextOrder = (board.cardsInColumn(columnId).maxOfOrNull { it.order } ?: -1) + 1
            AtomicLong(nextOrder.toLong())
        }
        return sequence.fetchAndAdd(1).toInt()
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}

// ─── State ──────────────────────────────────────────────────────────────────

@Serializable
data class ForgeBoardState(
    val boards: Map<KanbanBoardId, KanbanBoard> = emptyMap(),
    val activeBoardId: KanbanBoardId? = null,
    val dragState: DragState? = null,
    val lastEventKind: String = "INIT",
    val lastEventMs: Long = 0L,
) {
    val activeBoard: KanbanBoard? get() = activeBoardId?.let { boards[it] }
}

@Serializable
data class DragState(
    val boardId: KanbanBoardId,
    val cardId: KanbanCardId,
    val fromColumnId: KanbanColumnId,
    val overColumnId: KanbanColumnId?,
)

// ─── Events ─────────────────────────────────────────────────────────────────

@Serializable
sealed class ForgeBoardEvent {
    abstract val timestampMs: Long

    @Serializable data class BoardLoaded(
        val board: KanbanBoard,
        override val timestampMs: Long,
    ) : ForgeBoardEvent()

    @Serializable data class BoardSelected(
        val boardId: KanbanBoardId,
        override val timestampMs: Long,
    ) : ForgeBoardEvent()

    @Serializable data class CardMoved(
        val boardId: KanbanBoardId,
        val cardId: KanbanCardId,
        val toColumnId: KanbanColumnId,
        override val timestampMs: Long,
    ) : ForgeBoardEvent()

    @Serializable data class CardCreated(
        val boardId: KanbanBoardId,
        val cardId: KanbanCardId,
        val columnId: KanbanColumnId,
        val title: String,
        val description: String = "",
        val priority: CardPriority = CardPriority.MEDIUM,
        val assignee: String? = null,
        override val timestampMs: Long,
    ) : ForgeBoardEvent()

    @Serializable data class CardDeleted(
        val boardId: KanbanBoardId,
        val cardId: KanbanCardId,
        override val timestampMs: Long,
    ) : ForgeBoardEvent()

    @Serializable data class CardUpdated(
        val boardId: KanbanBoardId,
        val card: KanbanCard,
        override val timestampMs: Long,
    ) : ForgeBoardEvent()

    // Drag-and-drop lifecycle
    @Serializable data class DragStarted(
        val boardId: KanbanBoardId,
        val cardId: KanbanCardId,
        val fromColumnId: KanbanColumnId,
        override val timestampMs: Long,
    ) : ForgeBoardEvent()

    @Serializable data class DragOver(
        val overColumnId: KanbanColumnId,
        override val timestampMs: Long,
    ) : ForgeBoardEvent()

    @Serializable data class DragDropped(
        override val timestampMs: Long,
    ) : ForgeBoardEvent()

    @Serializable data class DragCancelled(
        override val timestampMs: Long,
    ) : ForgeBoardEvent()
}
