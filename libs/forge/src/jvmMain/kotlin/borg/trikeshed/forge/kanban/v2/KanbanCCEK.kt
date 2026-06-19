package borg.trikeshed.forge.kanban.v2

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.coroutines.CoroutineContext

object KeyPoolKey : CoroutineContext.Key<KeyPoolElement>
object OperationalDataPoolKey : CoroutineContext.Key<OperationalDataPoolElement>
object CoordinatorKey : CoroutineContext.Key<CoordinatorElement>

@Serializable data class KeyEntry(
    val keyId: String, val provider: String, val label: String,
    val modelUrl: String = "", val lastModel: String? = null,
    val lastUsedMs: Long = 0, val accessCount: Long = 0,
    val status: KeyStatus = KeyStatus.ACTIVE, val leasedTo: String? = null,
    val leaseExpiresAt: Long = 0,
)
@Serializable enum class KeyStatus { ACTIVE, BACKOFF, BENCHED }
@Serializable data class DraftMapping(val mapping: Map<String, String>, val updatedAt: Long = Instant.now().toEpochMilli())
@Serializable data class LeaseInfo(val keyId: String, val leasedTo: String?, val leaseStartedAt: Long, val leaseExpiresAt: Long, val isActiveLease: Boolean)

@Serializable data class OperationalEntry(
    val poolName: String, val key: String, val labels: Map<String, String> = emptyMap(),
    val value: Double = 0.0, val timestampMs: Long = Instant.now().toEpochMilli(),
    val metadata: Map<String, String> = emptyMap(),
)
object OperationalPool {
    const val AGENT_HEALTH = "agent_health"
    const val TASK_THROUGHPUT = "task_throughput"
    const val WORKER_UTILIZATION = "worker_utilization"
    const val LATENCY_DISTRIBUTION = "latency_distribution"
    const val ERROR_RATES = "error_rates"
    const val QUEUE_DEPTHS = "queue_depths"
    const val MODEL_USAGE = "model_usage"
    const val RESOURCE_USAGE = "resource_usage"
}
enum class AggregateReducer { SUM, AVG, MIN, MAX, COUNT, LATEST }

@Serializable data class CoordinatorConfig(
    val maxInProgress: Int = 3, val maxSpawn: Int = 3,
    val maxPerProvider: Int = 1,
    val leaseTtlMs: Long = 300_000, val tickIntervalMs: Long = 5_000,
    val queueDepth: Int = 0,
)
@Serializable data class CoordinatorState(
    val maxInProgress: Int, val maxSpawn: Int, val maxPerProvider: Int,
    val currentlyRunning: Int, val availableKeys: Int, val queueDepth: Int,
)
@Serializable data class DispatchResult(val spawned: Int, val reclaimed: Int, val promoted: Int, val crashed: Int, val timestampMs: Long)
@Serializable data class CoordinatorStats(
    val totalSpawned: Int, val totalReclaimed: Int, val totalPromoted: Int,
    val totalCrashed: Int, val lastTickMs: Long, val currentState: CoordinatorState,
)

class KeyPoolElement(parentJob: Job? = null) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = KeyPoolKey
    private val mutableEntries = ConcurrentHashMap<String, MutableEntry>()
    private val accessCount = AtomicLong(0)
    private val draftLock = ReentrantLock()
    data class MutableEntry(
        var keyId: String, var provider: String, var label: String,
        var modelUrl: String = "", var lastModel: String? = null,
        var lastUsedMs: Long = 0, var accessCount: Long = 0,
        var status: KeyStatus = KeyStatus.ACTIVE, var leasedTo: String? = null,
        var leaseStartedAt: Long = 0, var leaseExpiresAt: Long = 0,
        val semaphore: Channel<Unit> = Channel(1),
    )
    override suspend fun open() {
        super.open()
        mutableEntries.values.forEach { it.semaphore.trySend(Unit) }
    }
    override suspend fun close() {
        mutableEntries.values.forEach { it.semaphore.close() }
        mutableEntries.clear()
        super.close()
    }

    fun recordAccess(keyId: String, provider: String, label: String, modelUrl: String): KeyEntry {
        val now = Instant.now().toEpochMilli()
        val entry = mutableEntries.computeIfAbsent(keyId) { k ->
            MutableEntry(keyId=k, provider=provider, label=label, modelUrl=modelUrl, lastUsedMs=now, accessCount=1, status=KeyStatus.ACTIVE)
        }
        entry.lastUsedMs = now
        entry.accessCount++
        if (modelUrl.isNotBlank()) {
            entry.modelUrl = modelUrl
        }
        entry.status = KeyStatus.ACTIVE
        accessCount.incrementAndGet()
        return entry.toImmutable()
    }
    fun recordModel(keyId: String, model: String): KeyEntry? {
        val e = mutableEntries[keyId] ?: return null
        e.lastModel = model
        e.lastUsedMs = Instant.now().toEpochMilli()
        return e.toImmutable()
    }
    fun getAvailable(): List<KeyEntry> = mutableEntries.values.filter { it.status == KeyStatus.ACTIVE }.map { it.toImmutable() }
    fun getRunningPerProvider(): Map<String, Int> = mutableEntries.values.filter { it.status == KeyStatus.ACTIVE && it.leasedTo != null }.groupBy { it.provider }.mapValues { it.value.size }
    fun getAvailableForAgent(agentId: String): List<KeyEntry> {
        val now = Instant.now().toEpochMilli()
        return mutableEntries.values.filter { it.status == KeyStatus.ACTIVE }.filter { e ->
            if (e.leasedTo != null && e.leaseExpiresAt > 0L && now > e.leaseExpiresAt) {
                e.leasedTo = null
                e.leaseStartedAt = 0L
                e.leaseExpiresAt = 0L
            }
            e.leasedTo == null || e.leasedTo == agentId
        }.map { it.toImmutable() }
    }
    suspend fun acquireLease(keyId: String, agentId: String, ttlMs: Long = 0): Boolean {
        val e = mutableEntries[keyId] ?: return false
        if (e.status != KeyStatus.ACTIVE) return false
        val acquired = try { e.semaphore.receive(); true } catch (ex: Exception) { false }
        if (!acquired) return false
        try {
            val now = Instant.now().toEpochMilli()
            if (e.leasedTo != null && e.leaseExpiresAt > 0L && now > e.leaseExpiresAt) {
                e.leasedTo = null
                e.leaseStartedAt = 0L
                e.leaseExpiresAt = 0L
            }
            if (e.leasedTo == null || e.leasedTo == agentId) {
                e.leasedTo = agentId
                e.leaseStartedAt = now
                e.leaseExpiresAt = if (ttlMs > 0L) now + ttlMs else 0L
                return true
            } else {
                return false
            }
        } finally {
            e.semaphore.trySend(Unit)
        }
    }
    fun releaseLease(keyId: String, agentId: String): Boolean {
        val e = mutableEntries[keyId] ?: return false
        if (e.leasedTo == agentId) {
            e.leasedTo = null
            e.leaseStartedAt = 0L
            e.leaseExpiresAt = 0L
            return true
        }
        return false
    }
    fun getDraft(): DraftMapping {
        draftLock.lock()
        try {
            val activeKeysWithModels = mutableEntries.entries
                .filter { it.value.status == KeyStatus.ACTIVE && it.value.lastModel != null }
                .associate { it.key to it.value.lastModel!! }
            return DraftMapping(activeKeysWithModels)
        } finally {
            draftLock.unlock()
        }
    }
    fun getEntry(keyId: String): KeyEntry? = mutableEntries[keyId]?.toImmutable()
    fun loadFromCredentialPool(poolData: Map<String, List<Map<String, Any>>>): Int {
        var count = 0
        for ((provider, entries) in poolData) {
            for (entry in entries) {
                val keyId = entry["id"] as? String ?: continue
                val status = entry["last_status"] as? String ?: "active"
                if (status == "benched") continue
                val me = MutableEntry(
                    keyId = keyId,
                    provider = provider,
                    label = (entry["label"] as? String) ?: keyId,
                    modelUrl = (entry["base_url"] as? String) ?: "",
                    lastModel = (entry["last_model"] as? String) ?: (entry["last_success_model"] as? String),
                    lastUsedMs = (entry["last_used_at"] as? Long) ?: 0L,
                    accessCount = (entry["request_count"] as? Long) ?: 0L,
                    status = when (status) {
                        "ok" -> KeyStatus.ACTIVE
                        "exhausted" -> KeyStatus.BACKOFF
                        else -> KeyStatus.ACTIVE
                    }
                )
                mutableEntries[keyId] = me
                count++
            }
        }
        return count
    }
    fun totalAccessCount(): Long = accessCount.get()
    private fun MutableEntry.toImmutable(): KeyEntry = KeyEntry(keyId, provider, label, modelUrl, lastModel, lastUsedMs, accessCount, status, leasedTo, leaseExpiresAt)
}

class OperationalDataPoolElement(parentJob: Job? = null, private val maxHistoryPerKey: Int = 1000) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = OperationalDataPoolKey
    private val pools = ConcurrentHashMap<String, ConcurrentHashMap<String, OperationalEntry>>()
    private val history = ConcurrentHashMap<String, MutableList<OperationalEntry>>()
    private val lock = ReentrantLock()
    private val poolFlows = ConcurrentHashMap<String, MutableStateFlow<List<OperationalEntry>>>()
    override suspend fun open() { super.open(); poolFlows.values.forEach { it.value = emptyList() } }
    override suspend fun close() { pools.clear(); history.clear(); poolFlows.clear(); super.close() }

    fun record(poolName: String, key: String, labels: Map<String, String> = emptyMap(), value: Double = 0.0, metadata: Map<String, String> = emptyMap()): OperationalEntry {
        val now = Instant.now().toEpochMilli(); val entry = OperationalEntry(poolName, key, labels, value, now, metadata)
        lock.lock(); try { val pool = pools.computeIfAbsent(poolName) { ConcurrentHashMap() }; pool[key] = entry
            val hist = history.computeIfAbsent(poolName) { mutableListOf() }; hist.add(entry); if (hist.size > maxHistoryPerKey) hist.subList(0, hist.size - maxHistoryPerKey).clear()
            val flow = poolFlows.computeIfAbsent(poolName) { MutableStateFlow(emptyList()) }; flow.value = pool.values.toList(); return entry
        } finally { lock.unlock() }
    }
    fun get(poolName: String, key: String): OperationalEntry? = pools[poolName]?.get(key)
    fun query(poolName: String, labelFilters: Map<String, String> = emptyMap()): List<OperationalEntry> { val pool = pools[poolName] ?: return emptyList(); if (labelFilters.isEmpty()) return pool.values.toList(); return pool.values.filter { it.labels.all { (k,v) -> it.labels[k] == v } }.toList() }
    fun aggregate(poolName: String, labelKeys: List<String>, reducer: AggregateReducer = AggregateReducer.SUM): Map<List<String>, Double> {
        val pool = pools[poolName] ?: return emptyMap(); val groups = mutableMapOf<List<String>, MutableList<Double>>()
        for (e in pool.values) { val gk = labelKeys.map { e.labels[it] ?: "" }; groups.getOrPut(gk) { mutableListOf() }.add(e.value) }
        return groups.mapValues { (_, vals) -> when(reducer){AggregateReducer.SUM->vals.sum();AggregateReducer.AVG->if(vals.isEmpty())0.0 else vals.average();AggregateReducer.MIN->vals.minOrNull()?:0.0;AggregateReducer.MAX->vals.maxOrNull()?:0.0;AggregateReducer.COUNT->vals.size.toDouble();AggregateReducer.LATEST->{val latest=pool.values.maxByOrNull{it.timestampMs}; latest?.value?:0.0}} }
    }
    fun getFlow(poolName: String): StateFlow<List<OperationalEntry>> = poolFlows.computeIfAbsent(poolName) { MutableStateFlow(emptyList()) }.asStateFlow()
    fun getCombinedFlow(poolNames: List<String>): Flow<Map<String, List<OperationalEntry>>> {
        val flows = poolNames.map { getFlow(it) }
        return combine(flows) { values ->
            poolNames.zip(values).toMap()
        }
    }
}

class CoordinatorElement(parentJob: Job? = null, private val keyPoolElement: KeyPoolElement? = null, private val opsPoolElement: OperationalDataPoolElement? = null, private val config: CoordinatorConfig = CoordinatorConfig()) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = CoordinatorKey
    private val spawnedCount = AtomicInteger(0); private val reclaimedCount = AtomicInteger(0)
    private val promotedCount = AtomicInteger(0); private val crashedCount = AtomicInteger(0)
    private val lastTickTime = AtomicLong(0)
    private val _state = MutableStateFlow(CoordinatorState(maxInProgress=config.maxInProgress, maxSpawn=config.maxSpawn, maxPerProvider=config.maxPerProvider, currentlyRunning=0, availableKeys=0, queueDepth=0))
    val flowState: StateFlow<CoordinatorState> = _state.asStateFlow()
    private val keyPool: KeyPoolElement by lazy { keyPoolElement ?: error("KeyPoolElement required") }
    private val opsPool: OperationalDataPoolElement by lazy { opsPoolElement ?: error("OperationalDataPoolElement required") }
    fun tick(): DispatchResult {
        val runningPerProvider = keyPool.getRunningPerProvider()
        val availableEntries = keyPool.getAvailable().filter { runningPerProvider.getOrDefault(it.provider, 0) < config.maxPerProvider }
        val availableKeys = availableEntries.size
        val currentlyRunning = spawnedCount.get() - reclaimedCount.get()
        val canSpawn = minOf(config.maxSpawn, config.maxInProgress - currentlyRunning, availableKeys)
        var spawned = 0
        if (canSpawn > 0) {
            for (i in 0 until minOf(canSpawn, availableEntries.size)) {
                val e = availableEntries[i]
                val agentId = "agent-${spawnedCount.incrementAndGet()}"
                if (runBlocking { keyPool.acquireLease(e.keyId, agentId, config.leaseTtlMs) }) {
                    spawned++
                    runBlocking { opsPool.record(OperationalPool.TASK_THROUGHPUT, "spawned_${e.keyId}", mapOf("agent" to agentId, "key" to e.keyId), value=1.0) }
                }
            }
        }
        val now = Instant.now().toEpochMilli(); lastTickTime.set(now)
        _state.value = CoordinatorState(maxInProgress=config.maxInProgress, maxSpawn=config.maxSpawn, maxPerProvider=config.maxPerProvider, currentlyRunning=spawnedCount.get()-reclaimedCount.get(), availableKeys=availableKeys-spawned, queueDepth=config.queueDepth)
        runBlocking { opsPool.record(OperationalPool.TASK_THROUGHPUT, "tick_$now", mapOf("phase" to "tick"), value=spawned.toDouble()) }
        return DispatchResult(spawned, 0, 0, 0, now)
    }
    fun startLoop(scope: CoroutineScope = CoroutineScope(SupervisorJob())): Job = scope.launch { while (true) { tick(); delay(config.tickIntervalMs) } }
    fun getStats(): CoordinatorStats = CoordinatorStats(totalSpawned=spawnedCount.get(), totalReclaimed=reclaimedCount.get(), totalPromoted=promotedCount.get(), totalCrashed=crashedCount.get(), lastTickMs=lastTickTime.get(), currentState=_state.value)
    fun updateConfig(newConfig: CoordinatorConfig) { _state.value = _state.value.copy(maxInProgress=newConfig.maxInProgress, maxSpawn=newConfig.maxSpawn, maxPerProvider=newConfig.maxPerProvider) }
}

suspend fun installKanban(config: CoordinatorConfig = CoordinatorConfig(), maxHistoryPerKey: Int = 1000): KanbanElements {
    val kp = KeyPoolElement(); val op = OperationalDataPoolElement(maxHistoryPerKey = maxHistoryPerKey)
    val coord = CoordinatorElement(keyPoolElement=kp, opsPoolElement=op, config=config)
    kp.open(); op.open(); coord.open()
    return withContext(kp + op + coord) { KanbanElements(kp, op, coord) }
}

data class KanbanElements(val keyPool: KeyPoolElement, val opsPool: OperationalDataPoolElement, val coordinator: CoordinatorElement) : CoroutineContext by keyPool + opsPool + coordinator