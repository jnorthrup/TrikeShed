@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class KanbanKeyPool {
    @Serializable
    data class Entry(
        val keyId: String,
        val provider: String,
        val label: String,
        val modelUrl: String = "",
        var lastModel: String? = null,
        var lastUsedMs: Long = 0,
        var accessCount: Long = 0,
        var status: KeyStatus = KeyStatus.ACTIVE,
        var leasedTo: String? = null,
        var leaseExpiresAt: Long = 0,
    )

    private val mutex = Mutex()
    private val keys = mutableMapOf<String, Entry>()

    suspend fun recordAccess(
        keyId: String,
        provider: String,
        label: String,
        modelUrl: String = "",
    ): KeySnapshot = mutex.withLock {
        val now = platformUtils.currentTimeMillis()
        val entry = keys.getOrPut(keyId) {
            Entry(keyId, provider, label, modelUrl, lastUsedMs = now, accessCount = 1)
        }
        entry.lastUsedMs = now
        entry.accessCount++
        if (modelUrl.isNotEmpty()) entry.modelUrl = modelUrl
        entry.status = KeyStatus.ACTIVE
        entry.toSnapshot()
    }

    suspend fun recordModel(keyId: String, model: String): KeySnapshot? = mutex.withLock {
        keys[keyId]?.apply { lastModel = model; lastUsedMs = platformUtils.currentTimeMillis() }?.toSnapshot()
    }

    suspend fun markBackoff(keyId: String): KeySnapshot? = mutex.withLock {
        keys[keyId]?.apply { status = KeyStatus.BACKOFF }?.toSnapshot()
    }

    suspend fun markBenched(keyId: String): KeySnapshot? = mutex.withLock {
        keys[keyId]?.apply { status = KeyStatus.BENCHED }?.toSnapshot()
    }

    suspend fun availableKeys(): List<KeySnapshot> = mutex.withLock {
        keys.values.filter { it.status == KeyStatus.ACTIVE && it.leasedTo == null }.map { it.toSnapshot() }
    }

    suspend fun acquireLease(keyId: String, agentId: String, ttlMs: Long = 0): Boolean = mutex.withLock {
        val entry = keys[keyId] ?: return@withLock false
        if (entry.status != KeyStatus.ACTIVE || entry.leasedTo != null) return@withLock false
        val now = platformUtils.currentTimeMillis()
        entry.leasedTo = agentId
        entry.leaseExpiresAt = if (ttlMs > 0) now + ttlMs else 0
        entry.lastUsedMs = now
        true
    }

    suspend fun releaseLease(keyId: String, agentId: String): Boolean = mutex.withLock {
        val entry = keys[keyId] ?: return@withLock false
        if (entry.leasedTo == agentId) { entry.leasedTo = null; entry.leaseExpiresAt = 0; true } else false
    }

    suspend fun reclaimExpired(ttlMs: Long): Int = mutex.withLock {
        val now = platformUtils.currentTimeMillis()
        var count = 0
        for (entry in keys.values) {
            if (entry.leasedTo != null && entry.leaseExpiresAt > 0 && now > entry.leaseExpiresAt) {
                entry.leasedTo = null; entry.leaseExpiresAt = 0; count++
            }
        }
        count
    }

    suspend fun snapshots(): List<KeySnapshot> = mutex.withLock {
        keys.values.map { it.toSnapshot() }
    }

    companion object {
        @Volatile private var _instance: KanbanKeyPool? = null
        private val lock = Mutex()
        val instance: KanbanKeyPool get() = _instance ?: KanbanKeyPool()
        suspend fun get(): KanbanKeyPool { _instance?.let { return it }; lock.withLock { _instance ?: KanbanKeyPool().also { _instance = it } }; return _instance!! }
        fun reset() { _instance = null }
    }
}

@Serializable
enum class KeyStatus { ACTIVE, BACKOFF, BENCHED }

@Serializable
data class KeySnapshot(
    val keyId: String,
    val provider: String,
    val label: String,
    val modelUrl: String,
    val lastModel: String?,
    val lastUsedMs: Long,
    val accessCount: Long,
    val status: KeyStatus,
    val leasedTo: String?,
    val leaseExpiresAt: Long,
)

private fun KanbanKeyPool.Entry.toSnapshot() = KeySnapshot(
    keyId, provider, label, modelUrl, lastModel, lastUsedMs, accessCount, status, leasedTo, leaseExpiresAt,
)

@Serializable
data class DraftMapping(
    val mapping: Map<String, String>,
    val updatedAt: Long = platformUtils.currentTimeMillis(),
)
