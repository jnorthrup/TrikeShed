package borg.trikeshed.forge.kanban

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * In-memory pool of credential keys for kanban worker delegation.
 * Thread-safe with per-key semaphores for constraining hot spots.
 */
class KeyPool {
    private val draftLock = ReentrantLock()
    private val accessCount = AtomicLong(0)
    
    data class MutableEntry(
        var keyId: String,
        var provider: String,
        var label: String,
        var modelUrl: String = "",
        var lastModel: String? = null,
        var lastUsedMs: Long = 0,
        var accessCount: Long = 0,
        var status: KeyStatus = KeyStatus.ACTIVE,
        var leasedTo: String? = null,
        var leaseStartedAt: Long = 0,
        var leaseExpiresAt: Long = 0,
        val semaphore: kotlinx.coroutines.channels.Channel<Unit> = kotlinx.coroutines.channels.Channel(1)
    )
    
    private val mutableKeys = ConcurrentHashMap<String, MutableEntry>()
    
    /**
     * Record a key access. Creates entry if new, updates timestamp/count.
     */
    fun recordAccess(
        keyId: String,
        provider: String,
        label: String,
        modelUrl: String,
    ): KeyEntry {
        val now = Instant.now().toEpochMilli()
        val entry = mutableKeys.computeIfAbsent(keyId) { k ->
            MutableEntry(
                keyId = k,
                provider = provider,
                label = label,
                modelUrl = modelUrl,
                lastUsedMs = now,
                accessCount = 1,
                status = KeyStatus.ACTIVE,
            )
        }
        
        // Update existing
        entry.lastUsedMs = now
        entry.accessCount++
        if (modelUrl.isNotBlank()) entry.modelUrl = modelUrl
        entry.status = KeyStatus.ACTIVE
        
        accessCount.incrementAndGet()
        
        return entry.toImmutable()
    }
    
    /**
     * Record the model used by a key.
     */
    fun recordModel(keyId: String, model: String): KeyEntry? {
        val entry = mutableKeys[keyId] ?: return null
        entry.lastModel = model
        entry.lastUsedMs = Instant.now().toEpochMilli()
        return entry.toImmutable()
    }
    
    fun markBackoff(keyId: String): KeyEntry? {
        val entry = mutableKeys[keyId] ?: return null
        entry.status = KeyStatus.BACKOFF
        return entry.toImmutable()
    }
    
    fun markBenched(keyId: String): KeyEntry? {
        val entry = mutableKeys[keyId] ?: return null
        entry.status = KeyStatus.BENCHED
        return entry.toImmutable()
    }
    
    /**
     * Return bijective mapping of active keys to their last known model.
     */
    fun getDraft(): DraftMapping {
        draftLock.lock()
        try {
            val mapping = mutableKeys.entries
                .filter { it.value.status == KeyStatus.ACTIVE && it.value.lastModel != null }
                .associate { it.key to it.value.lastModel!! }
            return DraftMapping(mapping = mapping)
        } finally {
            draftLock.unlock()
        }
    }
    
    fun getAvailable(): List<KeyEntry> {
        return mutableKeys.values
            .filter { it.status == KeyStatus.ACTIVE }
            .map { it.toImmutable() }
    }
    
    fun getAvailableForAgent(agentId: String): List<KeyEntry> {
        val now = Instant.now().toEpochMilli()
        return mutableKeys.values
            .filter { it.status == KeyStatus.ACTIVE }
            .filter { entry ->
                // Expired lease?
                if (entry.leasedTo != null && entry.leaseExpiresAt > 0 && now > entry.leaseExpiresAt) {
                    entry.leasedTo = null
                    entry.leaseStartedAt = 0
                    entry.leaseExpiresAt = 0
                }
                // Free or leased to this agent
                entry.leasedTo == null || entry.leasedTo == agentId
            }
            .map { it.toImmutable() }
    }
    
    suspend fun acquireLease(
        keyId: String,
        agentId: String,
        ttlMs: Long = 0,
    ): Boolean {
        val entry = mutableKeys[keyId] ?: return false
        if (entry.status != KeyStatus.ACTIVE) return false
        
        val now = Instant.now().toEpochMilli()
        
        // Try to acquire semaphore
        val acquired = try {
            entry.semaphore.receive().fold(
                onSuccess = { true },
                onFailure = { false }
            )
        } catch (e: Exception) {
            false
        }
        
        if (!acquired) return false
        
        try {
            // Check lease again under semaphore
            if (entry.leasedTo != null && entry.leaseExpiresAt > 0 && now > entry.leaseExpiresAt) {
                entry.leasedTo = null
                entry.leaseStartedAt = 0
                entry.leaseExpiresAt = 0
            }
            
            if (entry.leasedTo == null || entry.leasedTo == agentId) {
                entry.leasedTo = agentId
                entry.leaseStartedAt = now
                entry.leaseExpiresAt = if (ttlMs > 0) now + ttlMs else 0
                return true
            }
            return false
        } finally {
            entry.semaphore.trySend(Unit)
        }
    }
    
    suspend fun acquireLeaseBlocking(
        keyId: String,
        agentId: String,
        ttlMs: Long = 0,
        timeoutMs: Long = 30_000,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val result = acquireLease(keyId, agentId, ttlMs)
            if (result) return@withTimeoutOrNull true
            delay(100)
        }
    } ?: false
    
    fun releaseLease(keyId: String, agentId: String): Boolean {
        val entry = mutableKeys[keyId] ?: return false
        if (entry.leasedTo == agentId) {
            entry.leasedTo = null
            entry.leaseStartedAt = 0
            entry.leaseExpiresAt = 0
            return true
        }
        return false
    }
    
    fun getLeaseInfo(keyId: String): LeaseInfo? {
        val entry = mutableKeys[keyId] ?: return null
        val now = Instant.now().toEpochMilli()
        return LeaseInfo(
            keyId = entry.keyId,
            leasedTo = entry.leasedTo,
            leaseStartedAt = entry.leaseStartedAt,
            leaseExpiresAt = entry.leaseExpiresAt,
            isActiveLease = entry.leasedTo != null && (entry.leaseExpiresAt == 0 || entry.leaseExpiresAt > now)
        )
    }
    
    fun getEntry(keyId: String): KeyEntry? {
        return mutableKeys[keyId]?.toImmutable()
    }
    
    fun loadFromCredentialPool(poolData: Map<String, List<Map<String, Any>>>): Int {
        var count = 0
        for ((provider, entries) in poolData) {
            for (entry in entries) {
                val keyId = entry["id"] as? String ?: continue
                val status = entry["last_status"] as? String ?: "active"
                if (status == "benched") continue
                
                val lastModel = (entry["last_model"] as? String) ?: (entry["last_success_model"] as? String)
                val modelUrl = (entry["base_url"] as? String) ?: (entry["inference_base_url"] as? String) ?: ""
                val lastUsedAt = (entry["last_used_at"] as? Long) ?: 0
                val requestCount = (entry["request_count"] as? Long) ?: 0
                
                val mutableEntry = MutableEntry(
                    keyId = keyId,
                    provider = provider,
                    label = (entry["label"] as? String) ?: keyId,
                    modelUrl = modelUrl,
                    lastModel = lastModel,
                    lastUsedMs = lastUsedAt,
                    accessCount = requestCount,
                    status = when (status) {
                        "ok" -> KeyStatus.ACTIVE
                        "exhausted" -> KeyStatus.BACKOFF
                        else -> KeyStatus.ACTIVE
                    },
                )
                
                mutableKeys[keyId] = mutableEntry
                count++
            }
        }
        return count
    }
    
    fun toList(): List<Map<String, Any>> {
        return mutableKeys.values.map { entry ->
            mapOf(
                "key_id" to entry.keyId,
                "provider" to entry.provider,
                "label" to entry.label,
                "model_url" to entry.modelUrl,
                "last_used_ms" to entry.lastUsedMs,
                "access_count" to entry.accessCount,
                "status" to entry.status.name,
                "last_model" to entry.lastModel,
                "leased_to" to entry.leasedTo,
                "lease_expires_at" to entry.leaseExpiresAt,
            )
        }
    }
    
    fun totalAccessCount(): Long = accessCount.get()
}

private fun KeyPool.MutableEntry.toImmutable(): KeyEntry {
    return KeyEntry(
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

/**
 * Global singleton accessor.
 */
object KeyPool {
    @Volatile private var INSTANCE: KeyPool? = null
    private val LOCK = Any()
    
    fun get(): KeyPool {
        return INSTANCE ?: LOCK.synchronized {
            INSTANCE ?: KeyPool().also { INSTANCE = it }
        }
    }
    
    fun reset() {
        LOCK.synchronized {
            INSTANCE = null
        }
    }
}