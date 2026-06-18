@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory pool for operational metrics with label-based grouping and history.
 * Provides reactive flows for dashboard visualization.
 * commonMain-safe: uses kotlinx.coroutines.sync.Mutex instead of ReentrantLock.
 */
class OperationalDataPool(
    private val maxHistoryPerKey: Int = 1000,
) {
    // ... (same class implementation)
    private val pools = ConcurrentHashMap<String, ConcurrentHashMap<String, OperationalEntry>>()
    private val history = ConcurrentHashMap<String, MutableList<OperationalEntry>>()
    private val mutex = Mutex()
    
    // Reactive flows for dashboard
    private val poolFlows = ConcurrentHashMap<String, MutableStateFlow<List<OperationalEntry>>>()
    
    /**
     * Record an operational metric. Creates pool/key if new.
     */
    suspend fun record(
        poolName: String,
        key: String,
        labels: Map<String, String> = emptyMap(),
        value: Double = 0.0,
        metadata: Map<String, String> = emptyMap(),
    ): OperationalEntry = mutex.withLock {
        val entry = OperationalEntry(
            poolName = poolName,
            key = key,
            labels = labels,
            value = value,
            timestampMs = platformUtils.currentTimeMillis(),
            metadata = metadata,
        )
        
        val pool = pools.computeIfAbsent(poolName) { ConcurrentHashMap() }
        pool[key] = entry
        
        val hist = history.computeIfAbsent(poolName) { mutableListOf() }
        hist.add(entry)
        if (hist.size > maxHistoryPerKey) {
            val removeCount = hist.size - maxHistoryPerKey
            repeat(removeCount) { hist.removeAt(0) }
        }
        
        // Emit to flow
        val flow = poolFlows.computeIfAbsent(poolName) { 
            MutableStateFlow(emptyList()) 
        }
        flow.value = pool.values.toList()
        
        entry
    }
    
    fun get(poolName: String, key: String): OperationalEntry? {
        return pools[poolName]?.get(key)
    }
    
    fun query(
        poolName: String,
        labelFilters: Map<String, String> = emptyMap(),
    ): List<OperationalEntry> {
        val pool = pools[poolName] ?: return emptyList()
        
        if (labelFilters.isEmpty()) {
            return pool.values.toList()
        }
        
        return pool.values.filter { entry ->
            labelFilters.all { (k, v) -> entry.labels[k] == v }
        }.toList()
    }
    
    fun aggregate(
        poolName: String,
        labelKeys: List<String>,
        reducer: AggregateReducer = AggregateReducer.SUM,
    ): Map<List<String>, Double> {
        val pool = pools[poolName] ?: return emptyMap()
        
        val groups = mutableMapOf<List<String>, MutableList<Double>>()
        for (entry in pool.values) {
            val groupKey = labelKeys.map { entry.labels[it] ?: "" }
            groups.getOrPut(groupKey) { mutableListOf() }.add(entry.value)
        }
        
        return groups.mapValues { (_, values) ->
            when (reducer) {
                AggregateReducer.SUM -> values.sum()
                AggregateReducer.AVG -> if (values.isEmpty()) 0.0 else values.average()
                AggregateReducer.MIN -> values.minOrNull() ?: 0.0
                AggregateReducer.MAX -> values.maxOrNull() ?: 0.0
                AggregateReducer.COUNT -> values.size.toDouble()
                AggregateReducer.LATEST -> {
                    val latestEntry = pool.values
                        .filter { entry ->
                            labelKeys.map { entry.labels[it] ?: "" } == groups.keys.firstOrNull { it == labelKeys.map { entry.labels[it] ?: "" } }
                        }
                        .maxByOrNull { it.timestampMs }
                    latestEntry?.value ?: 0.0
                }
            }
        }
    }
    
    suspend fun snapshot(): Map<String, List<OperationalEntry>> = mutex.withLock {
        val result = mutableMapOf<String, List<OperationalEntry>>()
        pools.forEach { (k, v) -> result[k] = v.values.toList() }
        return result
    }
    
    suspend fun clearPool(poolName: String): Int = mutex.withLock {
        val pool = pools.remove(poolName)
        val count = pool?.size ?: 0
        pool?.clear()
        history.remove(poolName)?.clear()
        poolFlows.remove(poolName)
        count
    }
    
    fun poolNames(): List<String> {
        return pools.keys.toList()
    }
    
    /**
     * Get reactive flow for a pool (for dashboard UI).
     */
    fun getFlow(poolName: String): StateFlow<List<OperationalEntry>> {
        return poolFlows.computeIfAbsent(poolName) { MutableStateFlow(emptyList()) }.asStateFlow()
    }
    
    /**
     * Get combined flow for multiple pools.
     */
    fun getCombinedFlow(poolNames: List<String>): StateFlow<Map<String, List<OperationalEntry>>> {
        val flows = poolNames.map { getFlow(it) }
        return combine(*flows.toTypedArray()) { values ->
            poolNames.zip(values).toMap()
        }.stateIn(
            scope = CoroutineScope(SupervisorJob()),
            started = SharingStarted.WhileSubscribed(),
            initialValue = emptyMap()
        )
    }
}

enum class AggregateReducer { SUM, AVG, MIN, MAX, COUNT, LATEST }

/**
 * Dashboard assertion for automated validation.
 */
@Serializable
data class DashboardAssertion(
    val viewId: String,
    val assertionType: AssertionType,
    val path: String,  // JSONPath
    @Contextual val expected: Any,
    val description: String = "",
)

@Serializable
enum class AssertionType {
    EQUALS, GREATER_THAN, LESS_THAN, BETWEEN, CONTAINS, 
    REGEX, IS_TRUE, IS_FALSE, IS_NONE, IS_NOT_NONE
}

// ---------------------------------------------------------------------------
// Module-level singleton accessor (coroutines-safe)
// ---------------------------------------------------------------------------

private class OperationalDataPoolHolder {
    var instance: OperationalDataPool? = null
    val mutex = Mutex()
}

private val _operationalDataPoolHolder = OperationalDataPoolHolder()

suspend fun getOperationalDataPool(maxHistoryPerKey: Int = 1000): OperationalDataPool = _operationalDataPoolHolder.mutex.withLock {
    _operationalDataPoolHolder.instance ?: OperationalDataPool(maxHistoryPerKey).also { _operationalDataPoolHolder.instance = it }
}

suspend fun resetOperationalDataPool() {
    _operationalDataPoolHolder.mutex.withLock {
        _operationalDataPoolHolder.instance = null
    }
}