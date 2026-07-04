package borg.trikeshed.userspace.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.reactor.KanbanFSM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

/**
 * Reactor-owned key/model muxer CCEK element.
 *
 * Kanban does not own keymux/modelmux state. The reactor owns this element and
 * downstream consumers (Forge UI, Kanban FSM projections, planners) observe the
 * immutable [flowState] snapshots or apply explicit events through this element.
 */
class MuxReactorElement(
    parentJob: Job? = null,
    initialConfig: MuxReactorConfig = MuxReactorConfig(),
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<MuxReactorElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private var config: MuxReactorConfig = initialConfig
    private var tickSequence: Long = 0
    private val keysById = mutableMapOf<String, MutableMuxKey>()
    private val modelsById = mutableMapOf<String, MuxModelEntry>()
    private val operationalHistory = mutableListOf<MuxOperationalEntry>()

    private val _flowState = MutableStateFlow(emptyState(initialConfig))
    val flowState: StateFlow<MuxReactorState> = _flowState.asStateFlow()

    /**
     * Reactor-owned modelmux cache layer. Holds model metadata and API call
     * results. Persists to ~/.hermes/model_cache.json via CacheStoreJvm on
     * the JVM. Emits CacheEvent values through [cacheEvents]; the UI and
     * the Kanban FSM consume them.
     */
    private val _cache: ModelApiCache = ModelApiCache()
    val cache: ModelApiCache get() = _cache
    val cacheEvents: SharedFlow<CacheEvent> get() = _cache.events

    private val _kanbanEvents = MutableSharedFlow<KanbanEvent>(
        replay = 64,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Reactor-owned kanban event stream. Kanban FSM consumes this; kanban never owns it. */
    val kanbanEvents: SharedFlow<KanbanEvent> = _kanbanEvents.asSharedFlow()

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            state = ElementState.ACTIVE
            publishState()
        }
    }

    override suspend fun close() {
        keysById.clear()
        modelsById.clear()
        operationalHistory.clear()
        super.close()
    }

    fun updateConfig(newConfig: MuxReactorConfig) {
        config = newConfig
        publishState()
    }

    fun recordAccess(
        keyId: String,
        provider: String,
        label: String = keyId,
        modelUrl: String = "",
    ): MuxKeyEntry {
        val now = nowMs()
        val entry = keysById.getOrPut(keyId) {
            MutableMuxKey(
                keyId = keyId,
                provider = provider,
                label = label,
                modelUrl = modelUrl,
                lastUsedMs = now,
                accessCount = 0,
            )
        }
        entry.provider = provider
        entry.label = label
        if (modelUrl.isNotBlank()) entry.modelUrl = modelUrl
        entry.lastUsedMs = now
        entry.accessCount += 1
        entry.status = MuxKeyStatus.ACTIVE
        publishState()
        return entry.toImmutable()
    }

    fun recordModel(
        keyId: String,
        modelId: String,
        provider: String = keysById[keyId]?.provider ?: "unknown",
        contextWindow: Int = 0,
    ): MuxKeyEntry? {
        val key = keysById[keyId] ?: return null
        key.lastModel = modelId
        key.lastUsedMs = nowMs()
        modelsById[modelId] = MuxModelEntry(
            modelId = modelId,
            provider = provider,
            contextWindow = contextWindow,
            available = true,
        )
        publishState()
        return key.toImmutable()
    }

    fun loadCredentialPool(poolData: Map<String, List<MuxCredentialRecord>>): Int {
            var loaded = 0
            poolData.forEach { (provider, entries) ->
                entries.forEach { credential ->
                    if (credential.id.isBlank()) return@forEach
                    if (credential.lastStatus == "benched") return@forEach
                    keysById[credential.id] = MutableMuxKey(
                        keyId = credential.id,
                        provider = provider,
                        label = credential.label.ifBlank { credential.id },
                        modelUrl = credential.baseUrl,
                        lastModel = credential.lastModel ?: credential.lastSuccessModel,
                        lastUsedMs = credential.lastUsedAt,
                        accessCount = credential.requestCount,
                        status = when (credential.lastStatus) {
                            "exhausted" -> MuxKeyStatus.BACKOFF
                            else -> MuxKeyStatus.ACTIVE
                        },
                    )
                    emitKanbanEvent(
                        KanbanEvent.CredentialLoaded(
                            provider = provider,
                            keyId = credential.id,
                            timestampMs = nowMs(),
                        ),
                    )
                    loaded++
                }
            }
            publishState()
            return loaded
        }

    private fun emitKanbanEvent(event: KanbanEvent) {
        // SharedFlow never blocks; emit is best-effort. Replay buffer keeps
        // late UI subscribers from missing the latest transition.
        _kanbanEvents.tryEmit(event)
    }

    /**
     * Reactor-owned ingress for external taxonomy creation events.
     * Callers submit taxonomy events here instead of emitting directly into the
     * public kanban SharedFlow.
     */
    fun ingestTaxonomyEvents(events: List<KanbanEvent.TaxonomyNodeCreated>): Int {
        events.forEach(::emitKanbanEvent)
        return events.size
    }

    /**
     * Look up a model in the reactor-owned modelmux cache. Emits a
     * KanbanEvent.CacheTick so the FSM records the hit/miss.
     */
    fun lookupModel(provider: String, modelId: String): CacheLookup {
        val lookup = _cache.lookupModel(provider, modelId)
        emitKanbanEvent(
            KanbanEvent.CacheTick(
                kind = if (lookup is CacheLookup.Hit) "Hit" else "Miss",
                cacheKey = lookup.key,
                timestampMs = lookup.timestampMs,
            ),
        )
        publishState()
        return lookup
    }

    /**
     * Store a model in the reactor-owned modelmux cache.
     */
    fun cacheModel(provider: String, modelId: String, payload: String): CacheEntry {
        val entry = _cache.putModel(provider, modelId, payload)
        emitKanbanEvent(
            KanbanEvent.CacheTick(
                kind = "Stored",
                cacheKey = entry.key,
                timestampMs = entry.storedAtMs,
            ),
        )
        publishState()
        return entry
    }

    /**
     * Look up an API call result in the reactor-owned modelmux cache.
     */
    fun lookupApiCall(
        provider: String,
        modelId: String,
        requestHash: String,
        ttlMs: Long,
    ): CacheLookup {
        val lookup = _cache.lookupApiCall(provider, modelId, requestHash, ttlMs)
        emitKanbanEvent(
            KanbanEvent.CacheTick(
                kind = if (lookup is CacheLookup.Hit) "Hit" else "Miss",
                cacheKey = lookup.key,
                timestampMs = lookup.timestampMs,
            ),
        )
        publishState()
        return lookup
    }

    /**
     * Store an API call result in the reactor-owned modelmux cache.
     */
    fun cacheApiCall(
        provider: String,
        modelId: String,
        requestHash: String,
        ttlMs: Long,
        payload: String,
    ): CacheEntry {
        val entry = _cache.putApiCall(provider, modelId, requestHash, ttlMs, payload)
        emitKanbanEvent(
            KanbanEvent.CacheTick(
                kind = "Stored",
                cacheKey = entry.key,
                timestampMs = entry.storedAtMs,
            ),
        )
        publishState()
        return entry
    }

    fun tick(): MuxDispatchResult {
        reclaimExpiredLeases()
        val runningPerProvider: Map<String, Int> = activeLeases()
            .mapNotNull { lease ->
                keysById[lease.keyId]?.provider?.let { it to 1 }
            }
            .groupBy { it.first }
            .mapValues { (_, entries) -> entries.size }
        val available = keysById.values
            .filter { it.status == MuxKeyStatus.ACTIVE && it.leasedTo == null }
            .filter { runningPerProvider.get(it.provider) ?: 0 < config.maxPerProvider }
        val running = activeLeases().size
        val canSpawn = minOf(config.maxSpawn, config.maxInProgress - running, available.size).coerceAtLeast(0)
        val now = nowMs()
        var spawned = 0
        for (entry in available.take(canSpawn)) {
            val agentId = "reactor-agent-${tickSequence + spawned + 1}"
            entry.leasedTo = agentId
            entry.leaseStartedAt = now
            entry.leaseExpiresAt = now + config.leaseTtlMs
            spawned++
            emitKanbanEvent(
                KanbanEvent.KeyLeased(
                    keyId = entry.keyId,
                    leasedTo = agentId,
                    leaseExpiresAt = entry.leaseExpiresAt,
                    timestampMs = now,
                ),
            )
            recordOperational(
                poolName = MuxOperationalPool.TASK_THROUGHPUT,
                key = "spawned_${entry.keyId}_$now",
                labels = mapOf("agent" to agentId, "key" to entry.keyId, "provider" to entry.provider),
                value = 1.0,
            )
        }
        tickSequence++
        recordOperational(
            poolName = MuxOperationalPool.TASK_THROUGHPUT,
            key = "tick_$now",
            labels = mapOf("phase" to "tick"),
            value = spawned.toDouble(),
        )
        publishState(now)
        return MuxDispatchResult(
            spawned = spawned,
            reclaimed = 0,
            promoted = 0,
            crashed = 0,
            timestampMs = now,
        )
    }

    fun releaseLease(keyId: String, leasedTo: String): Boolean {
        val key = keysById[keyId] ?: return false
        if (key.leasedTo != leasedTo) return false
        key.leasedTo = null
        key.leaseStartedAt = 0L
        key.leaseExpiresAt = 0L
        publishState()
        emitKanbanEvent(
            KanbanEvent.LeaseReclaimed(
                keyId = keyId,
                previousLeasee = leasedTo,
                timestampMs = nowMs(),
            ),
        )
        return true
    }

    fun startLoop(scope: CoroutineScope): Job = scope.launch {
        while (state.isLessThan(ElementState.DRAINING)) {
            tick()
            delay(config.tickIntervalMs)
        }
    }

    private fun recordOperational(
        poolName: String,
        key: String,
        labels: Map<String, String> = emptyMap(),
        value: Double = 0.0,
        metadata: Map<String, String> = emptyMap(),
    ): MuxOperationalEntry {
        val entry = MuxOperationalEntry(
            poolName = poolName,
            key = key,
            labels = labels,
            value = value,
            timestampMs = nowMs(),
            metadata = metadata,
        )
        operationalHistory.add(entry)
        if (operationalHistory.size > config.maxOperationalHistory) {
            operationalHistory.removeAt(0)
        }
        return entry
    }

    private fun reclaimExpiredLeases() {
        val now = nowMs()
        keysById.values.forEach { key ->
            if (key.leasedTo != null && key.leaseExpiresAt > 0L && now > key.leaseExpiresAt) {
                val previous = key.leasedTo
                key.leasedTo = null
                key.leaseStartedAt = 0L
                key.leaseExpiresAt = 0L
                emitKanbanEvent(
                    KanbanEvent.LeaseReclaimed(
                        keyId = key.keyId,
                        previousLeasee = previous ?: "",
                        timestampMs = now,
                    ),
                )
            }
        }
    }

    private fun activeLeases(): List<MuxLeaseInfo> {
        val now = nowMs()
        return keysById.values.mapNotNull { key ->
            val leasedTo = key.leasedTo ?: return@mapNotNull null
            MuxLeaseInfo(
                keyId = key.keyId,
                leasedTo = leasedTo,
                leaseStartedAt = key.leaseStartedAt,
                leaseExpiresAt = key.leaseExpiresAt,
                isActiveLease = key.leaseExpiresAt == 0L || now <= key.leaseExpiresAt,
            )
        }
    }

    private fun publishState(timestampMs: Long = nowMs()) {
        val keys = keysById.values.map { it.toImmutable() }
        val leases = activeLeases()
        _flowState.value = MuxReactorState(
            config = config,
            keys = keys,
            models = modelsById.values.toList(),
            leases = leases,
            operationalHistory = operationalHistory.toList(),
            draftMapping = keys
                .filter { it.status == MuxKeyStatus.ACTIVE && it.lastModel != null }
                .associate { it.keyId to it.lastModel.orEmpty() },
            currentlyRunning = leases.count { it.isActiveLease },
            availableKeys = keys.count { key -> key.status == MuxKeyStatus.ACTIVE && leases.none { it.keyId == key.keyId } },
            queueDepth = config.queueDepth,
            maxInProgress = config.maxInProgress,
            maxSpawn = config.maxSpawn,
            maxPerProvider = config.maxPerProvider,
            tickSequence = tickSequence,
            lastTickMs = timestampMs,
        )
    }

    private fun MutableMuxKey.toImmutable(): MuxKeyEntry = MuxKeyEntry(
        keyId = keyId,
        provider = provider,
        label = label,
        modelUrl = modelUrl,
        lastModel = lastModel,
        lastUsedMs = lastUsedMs,
        accessCount = accessCount,
        status = status,
        leasedTo = leasedTo,
        leaseExpiresAt = leaseExpiresAt,
    )
}

suspend fun openMuxReactorElement(
    config: MuxReactorConfig = MuxReactorConfig(),
    parentJob: Job? = null,
): MuxReactorElement = MuxReactorElement(parentJob = parentJob, initialConfig = config)
    .also { it.open() }

@Serializable
data class MuxReactorConfig(
    val maxInProgress: Int = 3,
    val maxSpawn: Int = 3,
    val maxPerProvider: Int = 1,
    val leaseTtlMs: Long = 300_000,
    val tickIntervalMs: Long = 5_000,
    val queueDepth: Int = 0,
    val maxOperationalHistory: Int = 1_000,
)

@Serializable
data class MuxReactorState(
    val config: MuxReactorConfig,
    val keys: List<MuxKeyEntry>,
    val models: List<MuxModelEntry>,
    val leases: List<MuxLeaseInfo>,
    val operationalHistory: List<MuxOperationalEntry>,
    val draftMapping: Map<String, String>,
    val currentlyRunning: Int,
    val availableKeys: Int,
    val queueDepth: Int,
    val maxInProgress: Int,
    val maxSpawn: Int,
    val maxPerProvider: Int,
    val tickSequence: Long,
    val lastTickMs: Long,
)

@Serializable
data class MuxKeyEntry(
    val keyId: String,
    val provider: String,
    val label: String,
    val modelUrl: String = "",
    val lastModel: String? = null,
    val lastUsedMs: Long = 0,
    val accessCount: Long = 0,
    val status: MuxKeyStatus = MuxKeyStatus.ACTIVE,
    val leasedTo: String? = null,
    val leaseExpiresAt: Long = 0,
)

@Serializable
enum class MuxKeyStatus { ACTIVE, BACKOFF, BENCHED }

@Serializable
data class MuxModelEntry(
    val modelId: String,
    val provider: String,
    val contextWindow: Int = 0,
    val available: Boolean = true,
)

@Serializable
data class MuxCredentialRecord(
    val id: String,
    val label: String = id,
    val baseUrl: String = "",
    val lastStatus: String? = null,
    val lastModel: String? = null,
    val lastSuccessModel: String? = null,
    val lastUsedAt: Long = 0,
    val requestCount: Long = 0,
)

@Serializable
data class MuxLeaseInfo(
    val keyId: String,
    val leasedTo: String,
    val leaseStartedAt: Long,
    val leaseExpiresAt: Long,
    val isActiveLease: Boolean,
)

@Serializable
data class MuxOperationalEntry(
    val poolName: String,
    val key: String,
    val labels: Map<String, String> = emptyMap(),
    val value: Double = 0.0,
    val timestampMs: Long = nowMs(),
    val metadata: Map<String, String> = emptyMap(),
)

object MuxOperationalPool {
    const val AGENT_HEALTH = "agent_health"
    const val TASK_THROUGHPUT = "task_throughput"
    const val WORKER_UTILIZATION = "worker_utilization"
    const val LATENCY_DISTRIBUTION = "latency_distribution"
    const val ERROR_RATES = "error_rates"
    const val QUEUE_DEPTHS = "queue_depths"
    const val MODEL_USAGE = "model_usage"
    const val RESOURCE_USAGE = "resource_usage"
}

@Serializable
data class MuxDispatchResult(
    val spawned: Int,
    val reclaimed: Int,
    val promoted: Int,
    val crashed: Int,
    val timestampMs: Long,
)

private data class MutableMuxKey(
    var keyId: String,
    var provider: String,
    var label: String,
    var modelUrl: String = "",
    var lastModel: String? = null,
    var lastUsedMs: Long = 0,
    var accessCount: Long = 0,
    var status: MuxKeyStatus = MuxKeyStatus.ACTIVE,
    var leasedTo: String? = null,
    var leaseStartedAt: Long = 0,
    var leaseExpiresAt: Long = 0,
)

private fun emptyState(config: MuxReactorConfig): MuxReactorState = MuxReactorState(
    config = config,
    keys = emptyList(),
    models = emptyList(),
    leases = emptyList(),
    operationalHistory = emptyList(),
    draftMapping = emptyMap(),
    currentlyRunning = 0,
    availableKeys = 0,
    queueDepth = config.queueDepth,
    maxInProgress = config.maxInProgress,
    maxSpawn = config.maxSpawn,
    maxPerProvider = config.maxPerProvider,
    tickSequence = 0,
    lastTickMs = 0,
)

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
