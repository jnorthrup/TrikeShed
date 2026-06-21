package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Kanban FSM events emitted by the reactor. Kanban consumes this stream but
 * does not own it; the reactor is the only producer.
 */
@Serializable
sealed class KanbanEvent {
    abstract val timestampMs: Long

    /** A credential was loaded from ~/.hermes and is now in the mux pool. */
    @Serializable
    data class CredentialLoaded(
        val provider: String,
        val keyId: String,
        override val timestampMs: Long,
    ) : KanbanEvent()

    /** A mux key was leased by a synthetic dispatcher agent. */
    @Serializable
    data class KeyLeased(
        val keyId: String,
        val leasedTo: String,
        val leaseExpiresAt: Long,
        override val timestampMs: Long,
    ) : KanbanEvent()

    /** A mux lease expired and the slot was reclaimed. */
    @Serializable
    data class LeaseReclaimed(
        val keyId: String,
        val previousLeasee: String,
        override val timestampMs: Long,
    ) : KanbanEvent()

    /**
     * Cache accounting event. Emitted by the reactor when the modelmux cache
     * observes a Hit, Miss, or Stored. The FSM rolls these into running
     * hit/miss/stored counters so the UI can show live modelmux state.
     */
    @Serializable
    data class CacheTick(
        val kind: String,            // "Hit", "Miss", "Stored", "Evicted"
        val cacheKey: String,
        override val timestampMs: Long,
    ) : KanbanEvent()
}

/**
 * Minimal Kanban board state derived from the reactor event stream. The FSM
 * lives in commonMain, has no Hermes coupling, and has no Python.
 *
 * State is intentionally compact: only the transitions a UI needs to render
 * "what just changed" on the board.
 */
@Serializable
data class KanbanState(
    val activeProviders: List<String> = emptyList(),
    val leasedKeyIds: List<String> = emptyList(),
    val reclaimedCount: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val cacheStored: Int = 0,
    val cacheEvicted: Int = 0,
    val lastCacheKey: String = "",
    val lastEventKind: String = "INIT",
    val lastEventTimestampMs: Long = 0L,
)

/**
 * Pure reducer: KanbanEvent -> KanbanState -> KanbanState.
 *
 * The reactor publishes events; consumers project state through this reducer.
 * The reducer is idempotent for the same event payload, so consumers can
 * replay the [SharedFlow] safely.
 */
object KanbanFSM {
    private val _state = MutableStateFlow(KanbanState())
    val flow: StateFlow<KanbanState> = _state.asStateFlow()

    /** SharedFlow of events emitted by the reactor. Replay buffer ensures late UI subscribers see recent transitions. */
    val kanbanEvents: MutableSharedFlow<KanbanEvent> = MutableSharedFlow(
        replay = 64,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun current(): KanbanState = _state.value

    /** Apply one event to the state. Returns the new state. */
    fun reduce(event: KanbanEvent, prior: KanbanState = current()): KanbanState {
        val next = when (event) {
            is KanbanEvent.CredentialLoaded -> prior.copy(
                activeProviders = (prior.activeProviders + event.provider).distinct(),
                lastEventKind = "CredentialLoaded",
                lastEventTimestampMs = event.timestampMs,
            )
            is KanbanEvent.KeyLeased -> prior.copy(
                leasedKeyIds = (prior.leasedKeyIds + event.keyId).distinct(),
                lastEventKind = "KeyLeased",
                lastEventTimestampMs = event.timestampMs,
            )
            is KanbanEvent.LeaseReclaimed -> prior.copy(
                leasedKeyIds = prior.leasedKeyIds - event.keyId,
                reclaimedCount = prior.reclaimedCount + 1,
                lastEventKind = "LeaseReclaimed",
                lastEventTimestampMs = event.timestampMs,
            )
            is KanbanEvent.CacheTick -> when (event.kind) {
                "Hit" -> prior.copy(
                    cacheHits = prior.cacheHits + 1,
                    lastCacheKey = event.cacheKey,
                    lastEventKind = "CacheHit",
                    lastEventTimestampMs = event.timestampMs,
                )
                "Miss" -> prior.copy(
                    cacheMisses = prior.cacheMisses + 1,
                    lastCacheKey = event.cacheKey,
                    lastEventKind = "CacheMiss",
                    lastEventTimestampMs = event.timestampMs,
                )
                "Stored" -> prior.copy(
                    cacheStored = prior.cacheStored + 1,
                    lastCacheKey = event.cacheKey,
                    lastEventKind = "CacheStored",
                    lastEventTimestampMs = event.timestampMs,
                )
                "Evicted" -> prior.copy(
                    cacheEvicted = prior.cacheEvicted + 1,
                    lastCacheKey = event.cacheKey,
                    lastEventKind = "CacheEvicted",
                    lastEventTimestampMs = event.timestampMs,
                )
                else -> prior.copy(
                    lastCacheKey = event.cacheKey,
                    lastEventKind = "CacheTick:${event.kind}",
                    lastEventTimestampMs = event.timestampMs,
                )
            }
        }
        _state.value = next
        return next
    }

    /** Reset the FSM to initial state. For test isolation only. */
    fun reset() {
        _state.value = KanbanState()
    }

    /** Convenience for folding a SharedFlow of events into the running state. */
    suspend fun collectAndReduce(events: SharedFlow<KanbanEvent>) {
        events.collect { event -> _state.value = reduce(event) }
    }
}