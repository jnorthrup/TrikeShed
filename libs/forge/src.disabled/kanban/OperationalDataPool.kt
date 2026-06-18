package borg.trikeshed.forge.kanban

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * In-memory pool for operational metrics with label-based grouping and history.
 * Provides reactive flows for dashboard visualization.
 */
class OperationalDataPool(
    private val maxHistoryPerKey: Int = 1000,
) {
    private val pools = ConcurrentHashMap<String, ConcurrentHashMap<String, OperationalEntry>>()
    private val history = ConcurrentHashMap<String, MutableList<OperationalEntry>>()
    private val lock = ReentrantLock()
    
    // Reactive flows for dashboard
    private val poolFlows = ConcurrentHashMap<String, MutableStateFlow<List<OperationalEntry>>>()
    
    /**
     * Record an operational metric. Creates pool/key if new.
     */
    fun record(
        poolName: String,
        key: String,
        labels: Map<String, String> = emptyMap(),
        value: Double = 0.0,
        metadata: Map<String, String> = emptyMap(),
    ): OperationalEntry {
        val now = Instant.now().toEpochMilli()
        val entry = OperationalEntry(
            poolName = poolName,
            key = key,
            labels = labels,
            value = value,
            timestampMs = now,
            metadata = metadata,
        )
        
        lock.lock()
        try {
            val pool = pools.computeIfAbsent(poolName) { ConcurrentHashMap() }
            pool[key] = entry
            
            val hist = history.computeIfAbsent(poolName) { mutableListOf() }
            hist.add(entry)
            if (hist.size > maxHistoryPerKey) {
                hist.removeRange(0, hist.size - maxHistoryPerKey)
            }
            
            // Emit to flow
            val flow = poolFlows.computeIfAbsent(poolName) { 
                MutableStateFlow(emptyList()) 
            }
            flow.value = pool.values.toList()
            
            return entry
        } finally {
            lock.unlock()
        }
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
    
    fun snapshot(): Map<String, List<OperationalEntry>> {
        lock.lock()
        try {
            return pools.entries.mapValues { (_, pool) -> pool.values.toList() }.toMap()
        } finally {
            lock.unlock()
        }
    }
    
    fun clearPool(poolName: String): Int {
        lock.lock()
        try {
            val pool = pools.remove(poolName)
            val count = pool?.size ?: 0
            pool?.clear()
            history.remove(poolName)?.clear()
            poolFlows.remove(poolName)
            return count
        } finally {
            lock.unlock()
        }
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
        }.asStateFlow()
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
    val expected: Any,
    val description: String = "",
)

@Serializable
enum class AssertionType {
    EQUALS, GREATER_THAN, LESS_THAN, BETWEEN, CONTAINS, 
    REGEX, IS_TRUE, IS_FALSE, IS_NONE, IS_NOT_NONE
}

/**
 * Global singleton accessor.
 */
object OperationalDataPool {
    @Volatile private var INSTANCE: OperationalDataPool? = null
    private val LOCK = Any()
    
    fun get(maxHistoryPerKey: Int = 1000): OperationalDataPool {
        return INSTANCE ?: LOCK.synchronized {
            INSTANCE ?: OperationalDataPool(maxHistoryPerKey).also { INSTANCE = it }
        }
    }
    
    fun reset() {
        LOCK.synchronized {
            INSTANCE = null
        }
    }
}