@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class KanbanCoordinator(
    private val keyPool: KanbanKeyPool = KanbanKeyPool.instance,
    private val config: CoordinatorConfig = CoordinatorConfig(),
) {
    private val mutex = Mutex()
    private var spawnedTotal = 0
    private var reclaimedTotal = 0
    private var promotedTotal = 0
    private var crashedTotal = 0
    private var lastTickMs: Long = 0

    private val _state = MutableStateFlow(
        CoordinatorState(
            maxInProgress = config.maxInProgress,
            maxSpawn = config.maxSpawn,
            currentlyRunning = 0,
            availableKeys = 0,
            queueDepth = 0,
            tickCount = 0,
        )
    )
    val state: StateFlow<CoordinatorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<KanbanDispatchEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<KanbanDispatchEvent> = _events.asSharedFlow()

    suspend fun tick(policy: DispatchPolicy = parsePolicy(SEED_POLICY)): DispatchResult = mutex.withLock {
        val now = platformUtils.currentTimeMillis()
        val available = keyPool.availableKeys()
        val doing = spawnedTotal - reclaimedTotal
        val canSpawn = minOf(policy.maxSpawn, policy.maxInProgress - doing, available.size)

        var spawned = 0
        var reclaimed = 0

        if (canSpawn > 0) {
            val toUse = available.take(canSpawn)
            for (key in toUse) {
                val agentId = "agent-${spawnedTotal + 1}"
                val acquired = keyPool.acquireLease(key.keyId, agentId, policy.leaseTtlMs)
                if (acquired) {
                    spawnedTotal++
                    spawned++
                    _events.emit(KanbanDispatchEvent.AgentSpawned(key.keyId, agentId, now))
                }
            }
        }

        val expired = keyPool.reclaimExpired(policy.leaseTtlMs)
        reclaimed = expired
        reclaimedTotal += expired

        lastTickMs = now
        val tick = _state.value.tickCount + 1
        _state.value = CoordinatorState(
            maxInProgress = policy.maxInProgress,
            maxSpawn = policy.maxSpawn,
            currentlyRunning = spawnedTotal - reclaimedTotal,
            availableKeys = keyPool.availableKeys().size,
            queueDepth = _state.value.queueDepth,
            tickCount = tick,
        )

        DispatchResult(spawned, reclaimed, 0, 0, now, tick)
    }

    fun startLoop(scope: CoroutineScope, policy: DispatchPolicy = parsePolicy(SEED_POLICY)): Job = scope.launch {
        while (isActive) {
            tick(policy)
            delay(policy.tickIntervalMs)
        }
    }

    fun stats(): CoordinatorStats = CoordinatorStats(
        totalSpawned = spawnedTotal,
        totalReclaimed = reclaimedTotal,
        totalPromoted = promotedTotal,
        totalCrashed = crashedTotal,
        lastTickMs = lastTickMs,
        currentState = _state.value,
    )
}

val SEED_POLICY: String = """
max_in_progress=4
max_spawn=4
lease_ttl_ms=300000
tick_interval_ms=5000
backoff_on_error=true
promote_on_done=true
reclaim_blocked=true
priority_weight=1.5
util_target=0.70
""".trim()

@Serializable
data class CoordinatorConfig(
    val maxInProgress: Int = 3,
    val maxSpawn: Int = 3,
    val leaseTtlMs: Long = 300_000,
    val tickIntervalMs: Long = 5_000,
    val queueDepth: Int = 0,
)

@Serializable
data class DispatchPolicy(
    val maxInProgress: Int = 4,
    val maxSpawn: Int = 4,
    val leaseTtlMs: Long = 300_000,
    val tickIntervalMs: Long = 5_000,
    val backoffOnError: Boolean = true,
    val promoteOnDone: Boolean = true,
    val reclaimBlocked: Boolean = true,
    val priorityWeight: Double = 1.5,
    val utilTarget: Double = 0.70,
)

fun parsePolicy(text: String): DispatchPolicy {
    val map = mutableMapOf<String, String>()
    for (line in text.trim().lines()) {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) continue
        val eq = l.indexOf('=')
        if (eq < 0) continue
        map[l.substring(0, eq).trim()] = l.substring(eq + 1).trim()
    }
    return DispatchPolicy(
        maxInProgress = map["max_in_progress"]?.toIntOrNull() ?: 4,
        maxSpawn = map["max_spawn"]?.toIntOrNull() ?: 4,
        leaseTtlMs = map["lease_ttl_ms"]?.toLongOrNull() ?: 300_000L,
        tickIntervalMs = map["tick_interval_ms"]?.toLongOrNull() ?: 5_000L,
        backoffOnError = map["backoff_on_error"]?.toBooleanStrictOrNull() ?: true,
        promoteOnDone = map["promote_on_done"]?.toBooleanStrictOrNull() ?: true,
        reclaimBlocked = map["reclaim_blocked"]?.toBooleanStrictOrNull() ?: true,
        priorityWeight = map["priority_weight"]?.toDoubleOrNull() ?: 1.5,
        utilTarget = map["util_target"]?.toDoubleOrNull() ?: 0.70,
    )
}

@Serializable
data class CoordinatorState(
    val maxInProgress: Int,
    val maxSpawn: Int,
    val currentlyRunning: Int,
    val availableKeys: Int,
    val queueDepth: Int,
    val tickCount: Int = 0,
)

@Serializable
data class DispatchResult(
    val spawned: Int,
    val reclaimed: Int,
    val promoted: Int,
    val crashed: Int,
    val timestampMs: Long,
    val tick: Int,
)

@Serializable
data class CoordinatorStats(
    val totalSpawned: Int,
    val totalReclaimed: Int,
    val totalPromoted: Int,
    val totalCrashed: Int,
    val lastTickMs: Long,
    val currentState: CoordinatorState,
)

sealed interface KanbanDispatchEvent {
    val timestampMs: Long
    data class AgentSpawned(val keyId: String, val agentId: String, override val timestampMs: Long) : KanbanDispatchEvent
    data class AgentReclaimed(val keyId: String, val agentId: String?, override val timestampMs: Long) : KanbanDispatchEvent
    data class AgentPromoted(val agentId: String, override val timestampMs: Long) : KanbanDispatchEvent
    data class AgentCrashed(val agentId: String, val reason: String, override val timestampMs: Long) : KanbanDispatchEvent
}
