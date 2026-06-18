@file:Suppress("unused")

package borg.trikeshed.forge.gateway

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

/**
 * Hermes Agent Gateway -- abstract protocol for borrowing Hermes operational data.
 *
 * NOT a direct coupling to Hermes internals. This is a protocol interface that
 * any agent gateway can implement (HTTP, WebSocket, IPC, IPFS pubsub, git CAS).
 * The kanban coordinator and forge runner consume this interface; they never
 * import Hermes code.
 *
 * Protocol variants:
 * - [GatewayProtocol.HTTP]   -- REST API polling
 * - [GatewayProtocol.WS]     -- WebSocket streaming
 * - [GatewayProtocol.IPC]    -- local Unix socket / pipe
 * - [GatewayProtocol.IPFS]   -- IPFS pubsub + content-addressed blocks
 * - [GatewayProtocol.GIT_CAS] -- git object store as CAS
 */
interface HermesAgentGateway {
    val protocol: GatewayProtocol
    val endpoint: String

    /** Pull task definitions from the remote system. */
    suspend fun fetchTasks(): List<RemoteTask>

    /** Push a task completion back to the remote system. */
    suspend fun reportCompletion(taskId: String, outcome: RemoteOutcome)

    /** Subscribe to live task events (creates, completions, blocks). */
    fun taskEvents(): Flow<RemoteTaskEvent>

    /** Fetch operational metrics from the remote system. */
    suspend fun fetchMetrics(): RemoteMetrics

    /** Check gateway health. */
    suspend fun health(): GatewayHealth
}

enum class GatewayProtocol { HTTP, WS, IPC, IPFS, GIT_CAS }

@Serializable
data class RemoteTask(
    val id: String,
    val title: String,
    val body: String,
    val assignee: String? = null,
    val status: String = "ready",
    val priority: Int = 1,
    val dependencies: List<String> = emptyList(),
    val tags: Set<String> = emptySet(),
)

@Serializable
data class RemoteOutcome(
    val taskId: String,
    val success: Boolean,
    val summary: String,
    val artifacts: List<String> = emptyList(),
    val timestampMs: Long = platformUtils.currentTimeMillis(),
)

@Serializable
data class RemoteMetrics(
    val throughput: Double = 0.0,
    val latencyMs: Double = 0.0,
    val errorRate: Double = 0.0,
    val workerUtil: Double = 0.0,
    val queueDepth: Int = 0,
    val timestampMs: Long = platformUtils.currentTimeMillis(),
)

@Serializable
data class GatewayHealth(
    val alive: Boolean,
    val protocol: String,
    val latencyMs: Long,
    val message: String? = null,
)

sealed interface RemoteTaskEvent {
    val taskId: String
    val timestampMs: Long
    data class Created(override val taskId: String, val title: String, override val timestampMs: Long) : RemoteTaskEvent
    data class Completed(override val taskId: String, val outcome: String, override val timestampMs: Long) : RemoteTaskEvent
    data class Blocked(override val taskId: String, val reason: String, override val timestampMs: Long) : RemoteTaskEvent
    data class Unblocked(override val taskId: String, override val timestampMs: Long) : RemoteTaskEvent
    data class Assigned(override val taskId: String, val assignee: String, override val timestampMs: Long) : RemoteTaskEvent
}

// ---------------------------------------------------------------------------
// IPFS / Git CAS gateway -- content-addressed task persistence
// ---------------------------------------------------------------------------

/**
 * Content-addressed storage gateway for kanban state.
 *
 * Uses CID (Content IDentifier) to store and retrieve task state across
 * distributed nodes. Compatible with IPFS BlockStore and git object store.
 *
 * The kanban board serializes its state via Confix (cursor gateway for
 * structured trees), then puts the resulting bytes into CAS. The CID
 * becomes the board's distributed identity.
 */
interface ContentAddressedStore {
    /** Put data into CAS, returns the CID. */
    suspend fun put(data: ByteArray): String

    /** Get data from CAS by CID. */
    suspend fun get(cid: String): ByteArray?

    /** Check if CID exists in store. */
    suspend fun has(cid: String): Boolean

    /** Pin a CID to prevent garbage collection. */
    suspend fun pin(cid: String): Boolean

    /** List all pinned CIDs. */
    suspend fun pinned(): List<String>
}

// ---------------------------------------------------------------------------
// In-memory CAS implementation (commonMain-safe, for testing)
// ---------------------------------------------------------------------------

class MemoryCAS : ContentAddressedStore {
    private val store = mutableMapOf<String, ByteArray>()
    private val pinnedSet = mutableSetOf<String>()

    private fun hash(data: ByteArray): String {
        // Simple SHA-256-like hash using Kotlin stdlib (commonMain-safe)
        // Real impl would use platform-specific SHA-256 via expect/actual
        var h = 0L
        for (b in data) {
            h = h * 31L + (b.toInt() and 0xFF)
        }
        return "bafy${h.toString(16).padStart(32, '0')}"
    }

    override suspend fun put(data: ByteArray): String {
        val cid = hash(data)
        store[cid] = data
        return cid
    }

    override suspend fun get(cid: String): ByteArray? = store[cid]

    override suspend fun has(cid: String): Boolean = cid in store

    override suspend fun pin(cid: String): Boolean {
        pinnedSet.add(cid)
        return cid in store
    }

    override suspend fun pinned(): List<String> = pinnedSet.toList()
}

// ---------------------------------------------------------------------------
// Reactor choreography -- bridge gateway events to coroutine fanout
// ---------------------------------------------------------------------------

/**
 * Gateway reactor -- bridges a [HermesAgentGateway] into a coroutine-based
 * reactive fanout. Subscribers receive [RemoteTaskEvent] as a [Flow].
 *
 * This is the "reactor choreography over protocol gateways" pattern:
 * - The gateway protocol (HTTP/WS/IPFS/GIT) is abstracted away
 * - Events are normalized into [RemoteTaskEvent]
 * - Multiple subscribers can fan out independently
 * - The coordinator consumes events without knowing the transport
 */
class GatewayReactor(
    private val gateway: HermesAgentGateway,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<RemoteTaskEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<RemoteTaskEvent> = _events.asSharedFlow()

    private val _metrics = MutableStateFlow<RemoteMetrics?>(null)
    val metrics: StateFlow<RemoteMetrics?> = _metrics.asStateFlow()

    private var pumpJob: Job? = null

    /** Start the event pump. Polls gateway and emits events. */
    fun start(pollIntervalMs: Long = 5_000L) {
        pumpJob?.cancel()
        pumpJob = scope.launch {
            // Initial metrics fetch
            try { _metrics.value = gateway.fetchMetrics() } catch (_: Exception) {}

            // Subscribe to gateway events
            gateway.taskEvents().collect { event ->
                _events.emit(event)
            }
        }

        // Background metrics poll
        scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                try { _metrics.value = gateway.fetchMetrics() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        pumpJob?.cancel()
    }
}
