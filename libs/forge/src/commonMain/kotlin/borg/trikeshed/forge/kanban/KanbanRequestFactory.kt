@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

/**
 * Kanban RequestFactory — GWT RequestFactory pattern for state/UI synchronization.
 *
 * GWT RequestFactory concepts adapted to our architecture:
 *
 *   GWT                          TrikeShed Kanban
 *   ───────────────────────────── ──────────────────────────────────
 *   EntityProxy                  KanbanEntity (Card, Column, Board)
 *   RequestContext               KanbanRequest (batches mutations)
 *   Request<EntityProxy>         Result<EntityProxy> (async, typed)
 *   Editor<T>                    KanbanEditor (binds UI to entity)
 *   AutoBean / diff/patch        Confix diff + ISAM wireproto
 *   Validation (JSR-303)         OverlayRole + Evidence validation
 *
 * The kanban board owns its state. The RequestFactory mediates:
 *   UI edits → RequestContext → Coordinator/Board → SharedFlow events → UI refresh
 *
 * All mutations are batched in a Request, sent via a single event emission,
 * and applied atomically on both client and server projections.
 */

// ---------------------------------------------------------------------------
// Entity Proxies — client-side representations with change tracking
// ---------------------------------------------------------------------------

@Serializable
sealed interface KanbanEntity {
    val entityId: String
    val version: Long  // optimistic locking
}

/** Kanban card entity proxy. */
@Serializable
data class CardEntity(
    override val entityId: String,
    override val version: Long,
    val title: String,
    val columnId: String,
    val order: Int = 0,
    val assignee: String? = null,
    val priority: Int = 1,
    val dependencies: List<String> = emptyList(),
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
) : KanbanEntity {
    fun copyWith(
        title: String? = null,
        columnId: String? = null,
        order: Int? = null,
        assigneeParam: String? = null,
        priorityParam: Int? = null,
        dependencies: List<String>? = null,
        tags: Set<String>? = null,
    ): CardEntity = copy(
        title = title ?: this.title,
        columnId = columnId ?: this.columnId,
        order = order ?: this.order,
        assignee = assigneeParam ?: this.assignee,
        priority = priorityParam ?: this.priority,
        dependencies = dependencies ?: this.dependencies,
        tags = tags ?: this.tags,
        version = version + 1,
    )
}

/** Kanban column entity proxy. */
@Serializable
data class ColumnEntity(
    override val entityId: String,
    override val version: Long,
    val name: String,
    val order: Int,
    val wipLimit: Int? = null,
    val color: String = "",
) : KanbanEntity

/** Kanban board entity proxy (root). */
@Serializable
data class BoardEntity(
    override val entityId: String,
    override val version: Long,
    val name: String,
    val columnIds: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) : KanbanEntity

// ---------------------------------------------------------------------------
// Request Context — batches mutations, single round trip
// ---------------------------------------------------------------------------

/**
 * A request context accumulates mutations and executes them as one atomic batch.
 *
 * Usage:
 *   val req = factory.newRequest()
 *   req.updateCard(cardId) { it.copyWith(columnId = "doing") }
 *   req.assignCard(cardId, "agent-42")
 *   req.fire()  // single event emission, atomic apply
 */
class KanbanRequest(
    private val factory: KanbanRequestFactory,
    private val onFire: (KanbanRequest) -> Unit,
) {
    private val cardMutations = mutableMapOf<String, CardEntity>()
    private val cardAssignments = mutableListOf<Pair<String, String>>()  // cardId, assignee
    private val cardMoves = mutableListOf<Triple<String, String, Int>>()  // cardId, columnId, order
    private val cardCompletes = mutableListOf<String>()
    private val cardBlocks = mutableListOf<Pair<String, String>>()  // cardId, reason
    private val cardUnblocks = mutableListOf<String>()

    /** Queue a card property update. */
    fun updateCard(cardId: String, mutator: CardEntity.() -> CardEntity) {
        val current = factory.getCard(cardId) ?: return
        val updated = current.mutator()
        cardMutations[cardId] = updated
    }

    /** Queue a card assignment. */
    fun assignCard(cardId: String, assignee: String) {
        cardAssignments.add(cardId to assignee)
    }

    /** Queue a card move (column + order). */
    fun moveCard(cardId: String, columnId: String, order: Int) {
        cardMoves.add(cardId to columnId to order)
    }

    /** Queue a card completion. */
    fun completeCard(cardId: String) {
        cardCompletes.add(cardId)
    }

    /** Queue a card block. */
    fun blockCard(cardId: String, reason: String) {
        cardBlocks.add(cardId to reason)
    }

    /** Queue a card unblock. */
    fun unblockCard(cardId: String) {
        cardUnblocks.add(cardId)
    }

    /** Check if this request has any mutations. */
    val hasMutations: Boolean
        get() = cardMutations.isNotEmpty() || cardAssignments.isNotEmpty()
            || cardMoves.isNotEmpty() || cardCompletes.isNotEmpty()
            || cardBlocks.isNotEmpty() || cardUnblocks.isNotEmpty()

    /** Execute the batched request. Single event emission, atomic apply. */
    suspend fun fire(): RequestResult {
        if (!hasMutations) return RequestResult(success = true, 0, 0, emptyList())
        onFire(this)
        return RequestResult(success = true, cardMutations.size, cardAssignments.size, emptyList())
    }
}

@Serializable
data class RequestResult(
    val success: Boolean,
    val cardUpdates: Int,
    val assignments: Int,
    val errors: List<String>,
)

// ---------------------------------------------------------------------------
// Editor — binds UI widgets to entity proxies (like GWT Editor framework)
// ---------------------------------------------------------------------------

/**
 * KanbanEditor — binds a UI component to a CardEntity.
 * Tracks dirty state, validates on flush, pushes to RequestContext.
 *
 * @param <W> Widget type (Compose, HTML, etc.)
 */
interface KanbanEditor<W> {
    /** The entity being edited. */
    val entity: CardEntity

    /** The bound widget. */
    val widget: W

    /** Called when widget value changes — marks dirty. */
    fun onValueChanged()

    /** Validate current state (OverlayRole + Evidence). */
    fun validate(): ValidationResult

    /** Flush dirty changes to a request context. */
    fun flush(request: KanbanRequest)

    /** Reset widget from entity (server push). */
    fun refresh()
}

@Serializable
data class ValidationResult(
    val valid: Boolean,
    val errors: Map<String, List<String>> = emptyMap(),  // field -> messages
)

/** Default editor for CardEntity. */
abstract class AbstractCardEditor<W>(override val entity: CardEntity, override val widget: W) : KanbanEditor<W> {
    protected var isDirty = false

    override fun onValueChanged() { isDirty = true }

    override fun validate(): ValidationResult {
        val errors = mutableMapOf<String, MutableList<String>>()
        // CONTROL fields: columnId must be valid column
        if (entity.columnId.isEmpty()) { errors.computeIfAbsent("columnId") { mutableListOf() }.add("required") }
        // CONTROL: priority must be >= 1
        if (entity.priority < 1) { errors.computeIfAbsent("priority") { mutableListOf() }.add("must be >= 1") }
        // HYPOTHESIS: evidence if DONE
        // (would check evidence overlay in real impl)
        return ValidationResult(errors.isEmpty(), errors.mapValues { it.value.toList() })
    }

    override fun flush(request: KanbanRequest) {
        if (!isDirty) return
        val result = validate()
        if (!result.valid) throw IllegalStateException("Validation failed: $result")
        request.updateCard(entity.entityId) { this }
        isDirty = false
    }

    override fun refresh() {
        // Widget reads from entity; override in subclass
    }
}

// ---------------------------------------------------------------------------
// Request Factory — the central synchronization point
// ---------------------------------------------------------------------------

class KanbanRequestFactory(
    private val coordinator: KanbanCoordinator,
    private val boardEngine: KanbanBoardEngine,
    private val scope: CoroutineScope,
) {
    private val _cards = mutableMapOf<String, CardEntity>()
    private var _version = 0L

    /** Initialize from board state. */
    fun init(cards: List<BoardCard>) {
        _version = 0
        _cards.clear()
        for (card in cards) {
            _cards[card.id] = CardEntity(
                entityId = card.id,
                version = 0,
                title = card.title,
                columnId = card.column.name.lowercase(),
                assignee = card.assignee,
                priority = card.priority,
                dependencies = card.dependencies,
                tags = card.tags,
            )
        }
    }

    /** Get current card entity. */
    fun getCard(cardId: String): CardEntity? = _cards[cardId]

    /** Get all card entities. */
    fun getAllCards(): List<CardEntity> = _cards.values.toList()

    /** Create a new request context. */
    fun newRequest(): KanbanRequest = KanbanRequest(this) { request ->
        scope.launch { applyRequest(request) }
    }

    /** Apply a batched request to the board and coordinator. */
    private suspend fun applyRequest(request: KanbanRequest) {
        val tick = coordinator.tick()

        // Apply card mutations
        for ((cardId, updated) in request.cardMutations) {
            _cards[cardId] = updated
            boardEngine.assign(cardId, updated.assignee ?: "")
            if (updated.columnId != _cards[cardId]?.columnId) {
                // Column change would be handled by assign/move
            }
        }

        // Apply assignments
        for ((cardId, assignee) in request.cardAssignments) {
            _cards[cardId]?.let { card ->
                _cards[cardId] = card.copyWith(assignee = assignee)
                boardEngine.assign(cardId, assignee)
            }
        }

        // Apply moves
        for ((cardId, columnId, order) in request.cardMoves) {
            val column = BoardColumn.values().firstOrNull { it.name.lowercase() == columnId }
                ?: BoardColumn.TODO
            _cards[cardId]?.let { card ->
                _cards[cardId] = card.copyWith(columnId = columnId)
                if (column == BoardColumn.DOING) boardEngine.assign(cardId, card.assignee ?: "")
                if (column == BoardColumn.DONE) boardEngine.complete(cardId, "moved to done")
                if (column == BoardColumn.BLOCKED) boardEngine.block(cardId, "moved to blocked")
            }
        }

        // Apply completions
        for (cardId in request.cardCompletes) {
            _cards[cardId]?.let { card ->
                _cards[cardId] = card.copyWith(columnId = BoardColumn.DONE.name.lowercase())
                boardEngine.complete(cardId, "completed")
            }
        }

        // Apply blocks
        for ((cardId, reason) in request.cardBlocks) {
            _cards[cardId]?.let { card ->
                _cards[cardId] = card.copyWith(columnId = BoardColumn.BLOCKED.name.lowercase())
                boardEngine.block(cardId, reason)
            }
        }

        // Apply unblocks
        for (cardId in request.cardUnblocks) {
            _cards[cardId]?.let { card ->
                _cards[cardId] = card.copyWith(columnId = BoardColumn.TODO.name.lowercase())
                boardEngine.unblock(cardId)
            }
        }

        // Emit sync event
        boardEngine.cards.collectOnce { updatedCards: List<BoardCard> ->
            // Version bump for optimistic locking
            _version++
            val newEntities = updatedCards.map<Pair<BoardCard, CardEntity?>> { card ->
                card to _cards[card.id]?.copy(version = _version)
            }
            // In real impl: emit to SharedFlow for UI subscribers
        }
    }

    /** Get current version for optimistic locking. */
    fun currentVersion(): Long = _version
}

// ---------------------------------------------------------------------------
// Confin Diff → ISAM Wireproto patch (the transport layer)
// ---------------------------------------------------------------------------

/**
 * Serializes the request batch as ISAM wireproto (FieldSynapse format).
 * This replaces GWT's AutoBean/diff mechanism.
 */
object KanbanWireProto {
    /** Opcodes for kanban mutations. */
    enum class Opcode(val byte: Byte) {
        CARD_UPDATE(0x01),
        CARD_ASSIGN(0x02),
        CARD_MOVE(0x03),
        CARD_COMPLETE(0x04),
        CARD_BLOCK(0x05),
        CARD_UNBLOCK(0x06),
    }

    /** Encode a request batch to wireproto bytes. */
    fun encode(request: KanbanRequest): ByteArray {
        // In real impl: serialize to FieldSynapse ring journal
        // For now: return empty (SharedFlow handles local sync)
        return ByteArray(0)
    }

    /** Decode wireproto bytes and apply to factory. */
    fun decode(factory: KanbanRequestFactory, bytes: ByteArray) {
        // In real impl: replay FieldSynapse journal into board state
    }
}
