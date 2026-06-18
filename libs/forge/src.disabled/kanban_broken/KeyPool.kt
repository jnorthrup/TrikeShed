@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import borg.trikeshed.forge.platform.platformUtils
import borg.trikeshed.forge.kanban.v2.*

/**
 * In-memory pool of credential keys for kanban worker delegation.
 * Thread-safe with per-key semaphores for constraining hot spots.
 * commonMain-safe: uses kotlinx.coroutines.sync.Mutex instead of ReentrantLock,
 * platformUtils.currentTimeMillis() instead of java.time.Instant.
 */
class KeyPool {
    private val mutex = Mutex()
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
        val semaphore: Channel<Unit> = Channel(1),
    )

    private val mutableKeys = ConcurrentHashMap<String, MutableEntry>()

    /**
     * Record a key access. Creates entry if new, updates timestamp/count.
     */
    suspend fun recordAccess(
        keyId: String,
        provider: String,
        label: String,
        modelUrl: String,
    ): KeyEntry = mutex.withLock {
        val now = platformUtils.currentTimeMillis()
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

        entry.toImmutable()
    }

    /**
     * Record the model used by a key.
     */
    suspend fun recordModel(keyId: String, model: String): KeyEntry? = mutex.withLock {
        val entry = mutableKeys[keyId] ?: return null
        entry.lastModel = model
        entry.lastUsedMs = platformUtils.currentTimeMillis()
        entry.toImmutable()
    }

    suspend fun markBackoff(keyId: String): KeyEntry? = mutex.withLock {
        mutableKeys[keyId]?.apply { status = KeyStatus.BACKOFF }?.toImmutable()
    }

    suspend fun markBenched(keyId: String): KeyEntry? = mutex.withLock {
        mutableKeys[keyId]?.apply { status = KeyStatus.BENCHED }?.toImmutable()
    }

    /**
     * Return bijective mapping of active keys to their last known model.
     */
    suspend fun getDraft(): DraftMapping = mutex.withLock {
        val mapping = mutableKeys.entries
            .filter { it.value.status == KeyStatus.ACTIVE && it.value.lastModel != null }
            .associate { it.key to it.value.lastModel!! }
        DraftMapping(mapping = mapping)
    }

    suspend fun getAvailable(): List<KeyEntry> = mutex.withLock {
        mutableKeys.values
            .filter { it.status == KeyStatus.ACTIVE }
            .map { it.toImmutable() }
    }

    suspend fun getAvailableForAgent(agentId: String): List<KeyEntry> = mutex.withLock {
        val now = platformUtils.currentTimeMillis()
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
    ): Boolean = mutex.withLock {
        val entry = mutableKeys[keyId] ?: return false
        if (entry.status != KeyStatus.ACTIVE) return false

        val now = platformUtils.currentTimeMillis()

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
    ): Boolean {
        suspend fun attempt(): Boolean? {
            while (true) {
                val acquired = acquireLease(keyId, agentId, ttlMs)
                if (acquired) return true
                delay(100)
            }
        }
        return withTimeoutOrNull(timeoutMs.milliseconds) {
            attempt()
        } ?: false
    }

    suspend fun releaseLease(keyId: String, agentId: String): Boolean = mutex.withLock {
        val entry = mutableKeys[keyId] ?: return false
        if (entry.leasedTo == agentId) {
            entry.leasedTo = null
            entry.leaseStartedAt = 0
            entry.leaseExpiresAt = 0
            return true
        }
        return false
    }

    suspend fun getLeaseInfo(keyId: String): LeaseInfo? = mutex.withLock {
        val entry = mutableKeys[keyId] ?: return null
        val now = platformUtils.currentTimeMillis()
        LeaseInfo(
            keyId = entry.keyId,
            leasedTo = entry.leasedTo,
            leaseStartedAt = entry.leaseStartedAt,
            leaseExpiresAt = entry.leaseExpiresAt,
            isActiveLease = entry.leasedTo != null && (entry.leaseExpiresAt == 0L || entry.leaseExpiresAt > now)
        )
    }

    suspend fun getEntry(keyId: String): KeyEntry? = mutex.withLock {
        mutableKeys[keyId]?.toImmutable()
    }

    suspend fun loadFromCredentialPool(poolData: Map<String, List<Map<String, Any>>>): Int = mutex.withLock {
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

    suspend fun toList(): List<Map<String, Any>> = mutex.withLock {
        mutableKeys.values.map { entry ->
            mapOf<String, Any>(
                "key_id" to entry.keyId,
                "provider" to entry.provider,
                "label" to entry.label,
                "model_url" to entry.modelUrl,
                "last_used_ms" to entry.lastUsedMs,
                "access_count" to entry.accessCount,
                "status" to entry.status.name,
                "last_model" to (entry.lastModel as Any? ?: ""),
                "leased_to" to (entry.leasedTo as Any? ?: ""),
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
 * Module-level singleton accessor (coroutines-safe).
 * Top-level to avoid object declaration issues.
 */
private class KeyPoolHolder {
    var instance: KeyPool? = null
    val mutex = Mutex()
}

private val _keyPoolHolder = KeyPoolHolder()

suspend fun getKeyPool(): KeyPool = _keyPoolHolder.mutex.withLock {
    _keyPoolHolder.instance ?: KeyPool().also { _keyPoolHolder.instance = it }
}

suspend fun resetKeyPool() {
    _keyPoolHolder.mutex.withLock {
        _keyPoolHolder.instance = null
    }
}