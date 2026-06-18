package borg.trikeshed.forge.kanban.v2

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

object KeyPoolKey : CoroutineContext.Key<KeyPoolElement>
object OperationalDataPoolKey : CoroutineContext.Key<OperationalDataPoolElement>
object CoordinatorKey : CoroutineContext.Key<CoordinatorElement>
object GepaOptimizerKey : CoroutineContext.Key<GepaOptimizerElement>

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
    val leaseTtlMs: Long = 300_000, val tickIntervalMs: Long = 5_000,
    val queueDepth: Int = 0,
)
@Serializable data class CoordinatorState(
    val maxInProgress: Int, val maxSpawn: Int, val currentlyRunning: Int,
    val availableKeys: Int, val queueDepth: Int,
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
    override suspend fun open() { super.open(); mutableEntries.values.forEach { it.semaphore.trySend(Unit) } }
    override suspend fun close() { mutableEntries.values.forEach { it.semaphore.close() }; mutableEntries.clear(); super.close() }

    fun recordAccess(keyId: String, provider: String, label: String, modelUrl: String): KeyEntry {
        val now = Instant.now().toEpochMilli()
        val entry = mutableEntries.computeIfAbsent(keyId) { k -> MutableEntry(keyId=k, provider=provider, label=label, modelUrl=modelUrl, lastUsedMs=now, accessCount=1, status=KeyStatus.ACTIVE) }
        entry.lastUsedMs = now; entry.accessCount++; if (modelUrl.isNotBlank()) entry.modelUrl = modelUrl; entry.status = KeyStatus.ACTIVE; accessCount.incrementAndGet()
        return entry.toImmutable()
    }
    fun recordModel(keyId: String, model: String): KeyEntry? { val e = mutableEntries[keyId] ?: return null; e.lastModel = model; e.lastUsedMs = Instant.now().toEpochMilli(); return e.toImmutable() }
    fun getAvailable(): List<KeyEntry> = mutableEntries.values.filter { it.status == KeyStatus.ACTIVE }.map { it.toImmutable() }
    fun getAvailableForAgent(agentId: String): List<KeyEntry> {
        val now = Instant.now().toEpochMilli()
        return mutableEntries.values.filter { it.status == KeyStatus.ACTIVE }.filter { e ->
            if (e.leasedTo != null && e.leaseExpiresAt > 0 && now > e.leaseExpiresAt) { e.leasedTo = null; e.leaseStartedAt = 0; e.leaseExpiresAt = 0 }
            e.leasedTo == null || e.leasedTo == agentId
        }.map { it.toImmutable() }
    }
    suspend fun acquireLease(keyId: String, agentId: String, ttlMs: Long = 0): Boolean {
        val e = mutableEntries[keyId] ?: return false; if (e.status != KeyStatus.ACTIVE) return false
        val acquired = e.semaphore.receive().fold(onSuccess={true}, onFailure={false}); if (!acquired) return false
        try { val now = Instant.now().toEpochMilli(); if (e.leasedTo != null && e.leaseExpiresAt > 0 && now > e.leaseExpiresAt) { e.leasedTo = null; e.leaseStartedAt = 0; e.leaseExpiresAt = 0 }
            if (e.leasedTo == null || e.leasedTo == agentId) { e.leasedTo = agentId; e.leaseStartedAt = now; e.leaseExpiresAt = if (ttlMs > 0) now + ttlMs else 0; return true } else return false
        } finally { e.semaphore.trySend(Unit) }
    }
    fun releaseLease(keyId: String, agentId: String): Boolean { val e = mutableEntries[keyId] ?: return false; if (e.leasedTo == agentId) { e.leasedTo = null; e.leaseStartedAt = 0; e.leaseExpiresAt = 0; return true } return false }
    fun getDraft(): DraftMapping { draftLock.lock(); try { return DraftMapping(mutableEntries.entries.filter { it.value.status == KeyStatus.ACTIVE && it.value.lastModel != null }.associate { it.key to it.value.lastModel!! }) } finally { draftLock.unlock() } }
    fun getEntry(keyId: String): KeyEntry? = mutableEntries[keyId]?.toImmutable()
    fun loadFromCredentialPool(poolData: Map<String, List<Map<String, Any>>>): Int {
        var count = 0
        for ((provider, entries) in poolData) {
            for (entry in entries) { val keyId = entry["id"] as? String ?: continue; val status = entry["last_status"] as? String ?: "active"; if (status == "benched") continue
                val me = MutableEntry(keyId=keyId, provider=provider, label=(entry["label"] as? String)?:keyId, modelUrl=(entry["base_url"] as? String)?:"", lastModel=(entry["last_model"] as? String)?:(entry["last_success_model"] as? String), lastUsedMs=(entry["last_used_at"] as? Long)?:0, accessCount=(entry["request_count"] as? Long)?:0, status=when(status){"ok"->KeyStatus.ACTIVE;"exhausted"->KeyStatus.BACKOFF;else->KeyStatus.ACTIVE})
                mutableEntries[keyId] = me; count++
            }
        } ; return count
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
            val hist = history.computeIfAbsent(poolName) { mutableListOf() }; hist.add(entry); if (hist.size > maxHistoryPerKey) hist.removeRange(0, hist.size - maxHistoryPerKey)
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
    fun getCombinedFlow(poolNames: List<String>): StateFlow<Map<String, List<OperationalEntry>>> { val flows = poolNames.map { getFlow(it) }.toTypedArray(); return combine(*flows) { poolNames.zip(values).toMap() }.asStateFlow() }
}

class CoordinatorElement(parentJob: Job? = null, private val keyPoolElement: KeyPoolElement? = null, private val opsPoolElement: OperationalDataPoolElement? = null, private val config: CoordinatorConfig = CoordinatorConfig()) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = CoordinatorKey
    private val spawnedCount = AtomicInteger(0); private val reclaimedCount = AtomicInteger(0)
    private val promotedCount = AtomicInteger(0); private val crashedCount = AtomicInteger(0)
    private val lastTickTime = AtomicLong(0)
    private val _state = MutableStateFlow(CoordinatorState(maxInProgress=config.maxInProgress, maxSpawn=config.maxSpawn, currentlyRunning=0, availableKeys=0, queueDepth=0))
    val state: StateFlow<CoordinatorState> = _state.asStateFlow()
    private val keyPool: KeyPoolElement by lazy { keyPoolElement ?: error("KeyPoolElement required") }
    private val opsPool: OperationalDataPoolElement by lazy { opsPoolElement ?: error("OperationalDataPoolElement required") }

    fun tick(): DispatchResult {
        val availableKeys = keyPool.getAvailable().size
        val currentlyRunning = spawnedCount.get() - reclaimedCount.get()
        val canSpawn = minOf(config.maxSpawn, config.maxInProgress - currentlyRunning, availableKeys)
        var spawned = 0
        if (canSpawn > 0) { val entries = keyPool.getAvailable(); for (i in 0 until minOf(canSpawn, entries.size)) { val e = entries[i]; val agentId = "agent-${spawnedCount.incrementAndGet()}"
            if (runBlocking { keyPool.acquireLease(e.keyId, agentId, config.leaseTtlMs) }) { spawned++; runBlocking { opsPool.record(OperationalPool.TASK_THROUGHPUT, "spawned_${e.keyId}", mapOf("agent" to agentId, "key" to e.keyId), value=1.0) } }
        }}
        val now = Instant.now().toEpochMilli(); lastTickTime.set(now)
        _state.value = CoordinatorState(maxInProgress=config.maxInProgress, maxSpawn=config.maxSpawn, currentlyRunning=spawnedCount.get()-reclaimedCount.get(), availableKeys=availableKeys-spawned, queueDepth=config.queueDepth)
        runBlocking { opsPool.record(OperationalPool.TASK_THROUGHPUT, "tick_$now", mapOf("phase" to "tick"), value=spawned.toDouble()) }
        return DispatchResult(spawned, 0, 0, 0, now)
    }
    fun startLoop(scope: CoroutineScope = CoroutineScope(supervisorScope { null })): Job = scope.launch { while (true) { tick(); delay(config.tickIntervalMs) } }
    fun getStats(): CoordinatorStats = CoordinatorStats(totalSpawned=spawnedCount.get(), totalReclaimed=reclaimedCount.get(), totalPromoted=promotedCount.get(), totalCrashed=crashedCount.get(), lastTickMs=lastTickTime.get(), currentState=_state.value)
    fun updateConfig(newConfig: CoordinatorConfig) { _state.value = _state.value.copy(maxInProgress=newConfig.maxInProgress, maxSpawn=newConfig.maxSpawn) }
}

class GepaOptimizerElement(parentJob: Job? = null, private val coordinatorElement: CoordinatorElement? = null, private val opsPoolElement: OperationalDataPoolElement? = null, private val config: GepaConfig = GepaConfig()) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = GepaOptimizerKey
    private val _state = MutableStateFlow(GepaState()); val state: StateFlow<GepaState> = _state.asStateFlow()
    private var backgroundJob: Job? = null
    private val coordinator: CoordinatorElement by lazy { coordinatorElement ?: error("CoordinatorElement required") }
    private val opsPool: OperationalDataPoolElement by lazy { opsPoolElement ?: error("OperationalDataPoolElement required") }

    fun runCycle(): GepaResult { val rl = buildReflectionLm(); val res = runOptimization(config.seedPolicy, config.maxMetricCalls, rl); _state.value = _state.value.copy(running=false, cycleCount=_state.value.cycleCount+1, lastResult=res); return res }
    fun startLoop(scope: CoroutineScope = CoroutineScope(supervisorScope{null}), intervalSeconds: Int = config.intervalSeconds): Job {
        if (backgroundJob != null && backgroundJob!!.isActive) return backgroundJob!!
        _state.value = _state.value.copy(running=true)
        backgroundJob = scope.launch { while (true) { delay(intervalSeconds*1000L); if (!_state.value.running) break; try { runCycle() } catch (e: Exception) {} }; _state.value = _state.value.copy(running=false) }
        return backgroundJob!!
    }
    fun stopLoop(): GepaState { backgroundJob?.cancel(); backgroundJob = null; val fs = _state.value.copy(running=false); _state.value = fs; return fs }
    private fun runOptimization(seed: String, maxCalls: Int, rl: (Any)->String): GepaResult {
        var best = seed; var bestScore = Double.NEGATIVE_INFINITY; var calls = 0; var cands = 0
        val base = parsePolicy(seed); var curScore = evaluatePolicy(base); bestScore = curScore
        for (i in 1..maxCalls) { cands++; val cand = reflectAndMutate(best, rl); val score = evaluatePolicy(parsePolicy(cand)); if (score > bestScore) { bestScore = score; best = cand }; calls++ }
        return GepaResult(best, calls, cands, "gepa_run_${Instant.now().toEpochMilli()}")
    }
    private fun parsePolicy(s: String): Map<String,Double> { val p = mutableMapOf<String,Double>(); for (pair in s.split(",")) { val parts = pair.trim().split("="); if (parts.size==2) try { p[parts[0].trim()] = parts[1].trim().toDouble() } catch (e: NumberFormatException) {} }; return p }
    private fun evaluatePolicy(p: Map<String,Double>): Double {
        if (p.containsKey("max_in_progress")) coordinator.updateConfig(CoordinatorConfig(maxInProgress=p["max_in_progress"]!!.toInt(), maxSpawn=config.maxSpawn))
        if (p.containsKey("max_spawn")) coordinator.updateConfig(CoordinatorConfig(maxInProgress=config.maxInProgress, maxSpawn=p["max_spawn"]!!.toInt()))
        val res = coordinator.tick()
        val tp = opsPool.query(OperationalPool.TASK_THROUGHPUT).sumOf{it.value}
        val lat = opsPool.query(OperationalPool.LATENCY_DISTRIBUTION).sumOf{it.value}/1000.0
        val err = opsPool.query(OperationalPool.ERROR_RATES).sumOf{it.value}*100
        val wu = opsPool.query(OperationalPool.WORKER_UTILIZATION); val util = if (wu.isNotEmpty()) abs(wu.average{it.value}-0.7)*50 else 0.0
        return tp*10 - lat - err - util
    }
    private fun reflectAndMutate(policy: String, rl: (Any)->String): String = try { rl("Current policy: $policy\nObjective: Maximize kanban dispatch throughput while minimizing latency, error rates, and worker imbalance.\nPropose a mutated policy string (key=value, ...) with small adjustments.\nReturn only the policy string.").trim() } catch (e: Exception) { mutatePolicy(policy) }
    private fun mutatePolicy(policy: String): String { val ps = parsePolicy(policy); val ks = ps.keys.toList(); if (ks.isEmpty()) return policy; val k = ks.random(); val cur = ps[k]!!; val mut = when(k){"max_in_progress"->(cur+(Math.random()*2-1).toInt()).coerceIn(1,20).toDouble();"max_spawn"->(cur+(Math.random()*2-1).toInt()).coerceIn(1,20).toDouble();else->cur}; return ps.plus(k to mut).entries.joinToString(", "){ "${it.key}=${it.value}" } }
    private fun buildReflectionLm(): (Any)->String = { _ -> "latency_warning=5000, latency_critical=15000, error_warning=0.05, error_critical=0.10, worker_overload=0.90, worker_idle=0.10, max_in_progress=${config.maxInProgress}, max_spawn=${config.maxSpawn}" }
}

@Serializable data class GepaConfig(val seedPolicy: String = "latency_warning=5000, latency_critical=15000, error_warning=0.05, error_critical=0.10, worker_overload=0.90, worker_idle=0.10, max_in_progress=3, max_spawn=3", val maxMetricCalls: Int = 10, val intervalSeconds: Int = 300)
@Serializable data class GepaState(val running: Boolean = false, val cycleCount: Int = 0, val lastResult: GepaResult? = null)
@Serializable data class GepaResult(val bestCandidate: String, val totalMetricCalls: Int, val numCandidates: Int, val runDir: String, val timestampMs: Long = Instant.now().toEpochMilli())

suspend fun installKanban(config: CoordinatorConfig = CoordinatorConfig(), gepaConfig: GepaConfig = GepaConfig(), maxHistoryPerKey: Int = 1000): KanbanElements {
    val kp = KeyPoolElement(); val op = OperationalDataPoolElement(maxHistoryPerKey)
    val coord = CoordinatorElement(keyPoolElement=kp, opsPoolElement=op, config=config)
    val gp = GepaOptimizerElement(coordinatorElement=coord, opsPoolElement=op, config=gepaConfig)
    kp.open(); op.open(); coord.open(); gp.open()
    return withContext(kp + op + coord + gp) { KanbanElements(kp, op, coord, gp) }
}

data class KanbanElements(val keyPool: KeyPoolElement, val opsPool: OperationalDataPoolElement, val coordinator: CoordinatorElement, val gepa: GepaOptimizerElement) : CoroutineContext.Element by keyPool + opsPool + coordinator + gepa
