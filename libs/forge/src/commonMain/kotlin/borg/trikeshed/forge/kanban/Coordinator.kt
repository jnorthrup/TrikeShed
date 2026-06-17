package borg.trikeshed.forge.kanban

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Kanban coordinator — dispatches work to available keys (agents).
 * Integrates with KeyPool for lease management and OperationalDataPool for metrics.
 */
class Coordinator(
    private val keyPool: KeyPool = KeyPool.get(),
    private val opsPool: OperationalDataPool = OperationalDataPool.get(),
    private val config: CoordinatorConfig = CoordinatorConfig(),
) {
    private val spawnedCount = AtomicInteger(0)
    private val reclaimedCount = AtomicInteger(0)
    private val promotedCount = AtomicInteger(0)
    private val crashedCount = AtomicInteger(0)
    private val lastTickTime = AtomicLong(0)
    
    // Reactive state for UI
    private val _state = MutableStateFlow(CoordinatorState(
        maxInProgress = config.maxInProgress,
        maxSpawn = config.maxSpawn,
        currentlyRunning = 0,
        availableKeys = 0,
        queueDepth = 0,
    ))
    val state: kotlinx.coroutines.flow.StateFlow<CoordinatorState> = _state.asStateFlow()
    
    /**
     * Run one dispatch tick.
     */
    fun tick(): DispatchResult {
        val startTime = Instant.now().toEpochMilli()
        
        // 1. Get available keys (not leased to other agents)
        val availableKeys = keyPool.getAvailable().size
        
        // 2. Determine how many to spawn
        val currentlyRunning = spawnedCount.get() - reclaimedCount.get()
        val canSpawn = minOf(config.maxSpawn, config.maxInProgress - currentlyRunning, availableKeys)
        
        var spawned = 0
        var reclaimed = 0
        var promoted = 0
        var crashed = 0
        
        if (canSpawn > 0) {
            // In a real implementation, this would pull from kanban queue
            // For now, simulate spawning
            val entries = keyPool.getAvailable()
            for (i in 0 until minOf(canSpawn, entries.size)) {
                val entry = entries[i]
                val agentId = "agent-${spawnedCount.incrementAndGet()}"
                
                // Try to acquire lease
                runBlocking {
                    if (keyPool.acquireLease(entry.keyId, agentId, config.leaseTtlMs)) {
                        spawned++
                        
                        // Record metrics
                        opsPool.record(
                            OperationalPool.TASK_THROUGHPUT,
                            "spawned_${entry.keyId}",
                            mapOf("agent" to agentId, "key" to entry.keyId),
                            value = 1.0,
                        )
                    }
                }
            }
        }
        
        // 3. Check for completed/failed leases (reclaim)
        // In real impl, would check agent heartbeats
        
        // 4. Update state
        val now = Instant.now().toEpochMilli()
        lastTickTime.set(now)
        
        _state.value = CoordinatorState(
            maxInProgress = config.maxInProgress,
            maxSpawn = config.maxSpawn,
            currentlyRunning = spawnedCount.get() - reclaimedCount.get(),
            availableKeys = availableKeys - spawned,
            queueDepth = config.queueDepth,
        )
        
        // Record throughput metric
        opsPool.record(
            OperationalPool.TASK_THROUGHPUT,
            "tick_${now}",
            mapOf("phase" to "tick"),
            value = spawned.toDouble(),
        )
        
        return DispatchResult(
            spawned = spawned,
            reclaimed = reclaimed,
            promoted = promoted,
            crashed = crashed,
            timestampMs = now,
        )
    }
    
    /**
     * Start background dispatch loop.
     */
    fun startLoop(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)): Job {
        return scope.launch {
            while (true) {
                tick()
                delay(config.tickIntervalMs)
            }
        }
    }
    
    fun getStats(): CoordinatorStats {
        return CoordinatorStats(
            totalSpawned = spawnedCount.get(),
            totalReclaimed = reclaimedCount.get(),
            totalPromoted = promotedCount.get(),
            totalCrashed = crashedCount.get(),
            lastTickMs = lastTickTime.get(),
            currentState = _state.value,
        )
    }
    
    fun updateConfig(newConfig: CoordinatorConfig) {
        _state.value = _state.value.copy(
            maxInProgress = newConfig.maxInProgress,
            maxSpawn = newConfig.maxSpawn,
        )
    }
}

@Serializable
data class CoordinatorConfig(
    val maxInProgress: Int = 3,
    val maxSpawn: Int = 3,
    val leaseTtlMs: Long = 300_000,  // 5 minutes
    val tickIntervalMs: Long = 5_000,
    val queueDepth: Int = 0,
)

@Serializable
data class CoordinatorState(
    val maxInProgress: Int,
    val maxSpawn: Int,
    val currentlyRunning: Int,
    val availableKeys: Int,
    val queueDepth: Int,
)

@Serializable
data class DispatchResult(
    val spawned: Int,
    val reclaimed: Int,
    val promoted: Int,
    val crashed: Int,
    val timestampMs: Long,
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

/**
 * Global singleton accessor.
 */
object Coordinator {
    @Volatile private var INSTANCE: Coordinator? = null
    private val LOCK = Any()
    
    fun get(
        keyPool: KeyPool = KeyPool.get(),
        opsPool: OperationalDataPool = OperationalDataPool.get(),
        config: CoordinatorConfig = CoordinatorConfig(),
    ): Coordinator {
        return INSTANCE ?: LOCK.synchronized {
            INSTANCE ?: Coordinator(keyPool, opsPool, config).also { INSTANCE = it }
        }
    }
    
    fun reset() {
        LOCK.synchronized {
            INSTANCE = null
        }
    }
}