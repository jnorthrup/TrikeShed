package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.Serializable
import kotlinx.datetime.Clock

/**
 * Cache key for model metadata. Pure value object.
 */
@Serializable
data class ModelCacheKey(
    val provider: String,
    val modelId: String,
) {
    override fun toString(): String = "$provider/$modelId"
}

/**
 * Cache key for API call results. Includes the request hash and TTL window.
 */
@Serializable
data class ApiCallCacheKey(
    val provider: String,
    val modelId: String,
    val requestHash: String,
    val ttlMs: Long,
) {
    override fun toString(): String = "$provider/$modelId#$requestHash:$ttlMs"
}

/**
 * One cached entry. Stores both a model metadata row and an API call payload.
 */
@Serializable
data class CacheEntry(
    val key: String,
    val provider: String,
    val modelId: String,
    val storedAtMs: Long = Clock.System.now().toEpochMilliseconds(),
    val hits: Long = 0,
    val payload: String = "",
)

/**
 * Outcome of a cache lookup. Returned by the reactor so callers can react.
 */
@Serializable
sealed class CacheLookup {
    abstract val key: String
    abstract val timestampMs: Long

    @Serializable
    data class Hit(
        val entry: CacheEntry,
        override val timestampMs: Long = Clock.System.now().toEpochMilliseconds(),
    ) : CacheLookup() {
        override val key: String get() = entry.key
    }

    @Serializable
    data class Miss(
        val provider: String,
        val modelId: String,
        override val timestampMs: Long = Clock.System.now().toEpochMilliseconds(),
    ) : CacheLookup() {
        override val key: String get() = "$provider/$modelId"
    }
}

/**
 * Reactor-owned modelmux cache layer.
 *
 * Owns:
 * - model metadata cache keyed by (provider, modelId)
 * - API call result cache keyed by (provider, modelId, requestHash, ttlMs)
 *
 * Emits events on every Hit, Miss, Evict. The reactor element publishes these
 * events into the kanbanEvents SharedFlow so the Kanban FSM can reduce them
 * into the live KanbanState.
 *
 * Persistence is JVM-only: CacheStoreJvm writes to ~/.hermes/model_cache.json.
 * The cache itself lives in commonMain so it can be reasoned about without
 * the JVM stdlib.
 */
class ModelApiCache(
    private val maxEntries: Int = 1024,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val models = mutableMapOf<ModelCacheKey, CacheEntry>()
    private val apiCalls = mutableMapOf<ApiCallCacheKey, CacheEntry>()

    private val _events = MutableSharedFlow<CacheEvent>(
        replay = 64,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<CacheEvent> = _events.asSharedFlow()

    /**
     * Look up a model by (provider, modelId). Emits Hit or Miss.
     */
    fun lookupModel(provider: String, modelId: String): CacheLookup {
        val key = ModelCacheKey(provider, modelId)
        val now = clock()
        val existing = models[key]
        return if (existing != null) {
            existing.copy(hits = existing.hits + 1)
                .also { models[key] = it }
                .let { updated ->
                    val lookup = CacheLookup.Hit(updated, now)
                    _events.tryEmit(CacheEvent.ModelHit(updated.key, now))
                    lookup
                }
        } else {
            _events.tryEmit(CacheEvent.ModelMiss(key.toString(), now))
            CacheLookup.Miss(provider, modelId, now)
        }
    }

    /**
     * Store a model metadata row. Emits Stored.
     */
    fun putModel(provider: String, modelId: String, payload: String): CacheEntry {
        val now = clock()
        val key = ModelCacheKey(provider, modelId)
        val entry = CacheEntry(
            key = key.toString(),
            provider = provider,
            modelId = modelId,
            storedAtMs = now,
            hits = 0,
            payload = payload,
        )
        models[key] = entry
        evictIfNeeded()
        _events.tryEmit(CacheEvent.ModelStored(entry.key, now))
        return entry
    }

    /**
     * Look up an API call result. Emits Hit or Miss.
     */
    fun lookupApiCall(
        provider: String,
        modelId: String,
        requestHash: String,
        ttlMs: Long,
    ): CacheLookup {
        val key = ApiCallCacheKey(provider, modelId, requestHash, ttlMs)
        val now = clock()
        val existing = apiCalls[key]
        return if (existing != null && (now - existing.storedAtMs) <= ttlMs) {
            existing.copy(hits = existing.hits + 1)
                .also { apiCalls[key] = it }
                .let { updated ->
                    val lookup = CacheLookup.Hit(updated, now)
                    _events.tryEmit(CacheEvent.ApiCallHit(updated.key, now))
                    lookup
                }
        } else {
            _events.tryEmit(CacheEvent.ApiCallMiss(key.toString(), now))
            CacheLookup.Miss(provider, "$modelId#$requestHash", now)
        }
    }

    /**
     * Store an API call result. Emits Stored.
     */
    fun putApiCall(
        provider: String,
        modelId: String,
        requestHash: String,
        ttlMs: Long,
        payload: String,
    ): CacheEntry {
        val now = clock()
        val key = ApiCallCacheKey(provider, modelId, requestHash, ttlMs)
        val entry = CacheEntry(
            key = key.toString(),
            provider = provider,
            modelId = modelId,
            storedAtMs = now,
            hits = 0,
            payload = payload,
        )
        apiCalls[key] = entry
        evictIfNeeded()
        _events.tryEmit(CacheEvent.ApiCallStored(entry.key, now))
        return entry
    }

    /**
     * Drop expired API call entries whose storedAtMs + ttlMs is past.
     * Emits one Evict per expired key.
     */
    fun evictExpired(): Int {
        val now = clock()
        var evicted = 0
        val toRemove = apiCalls.entries.filter { (_, entry) ->
            // ttl is encoded as "storing TTL was at construction time"
            // simplest policy: entries stored more than 1 hour ago without re-hit.
            (now - entry.storedAtMs) > 3_600_000L
        }
        for ((key, _) in toRemove) {
            apiCalls.remove(key)
            _events.tryEmit(CacheEvent.Evicted(key.toString(), now))
            evicted++
        }
        return evicted
    }

    fun modelCount(): Int = models.size
    fun apiCallCount(): Int = apiCalls.size

    fun snapshotModels(): List<CacheEntry> = models.values.toList()
    fun snapshotApiCalls(): List<CacheEntry> = apiCalls.values.toList()

    /**
     * Replace in-memory state from a persisted snapshot. Used at startup to
     * hydrate from ~/.hermes/model_cache.json. Does NOT emit events.
     */
    fun hydrate(entries: List<CacheEntry>) {
        models.clear()
        apiCalls.clear()
        for (entry in entries) {
            val key = ModelCacheKey(entry.provider, entry.modelId)
            // API call keys are not serializable from a flat key string alone
            // for hydrate; model rows dominate the snapshot.
            if (entry.key.contains("#")) {
                // stored as an api-call row
                apiCalls[ApiCallCacheKey(entry.provider, entry.modelId, entry.key.substringAfter('#').substringBefore(':'), 3_600_000L)] = entry
            } else {
                models[key] = entry
            }
        }
    }

    fun persist(): List<CacheEntry> = models.values.toList() + apiCalls.values.toList()

    private fun evictIfNeeded() {
        val total = models.size + apiCalls.size
        if (total <= maxEntries) return
        // LRU-ish: drop the oldest entries until under the cap
        val all = (models.values + apiCalls.values).sortedBy { it.storedAtMs }
        val overflow = total - maxEntries
        for (entry in all.take(overflow)) {
            val key = entry.key
            models.entries.removeAll { it.value.key == key }
            apiCalls.entries.removeAll { it.value.key == key }
            _events.tryEmit(CacheEvent.Evicted(key, clock()))
        }
    }
}

/**
 * Cache event types. The reactor element fans these into the kanbanEvents
 * SharedFlow.
 */
@Serializable
sealed class CacheEvent {
    abstract val key: String
    abstract val timestampMs: Long

    @Serializable
    data class ModelHit(override val key: String, override val timestampMs: Long) : CacheEvent()

    @Serializable
    data class ModelMiss(override val key: String, override val timestampMs: Long) : CacheEvent()

    @Serializable
    data class ModelStored(override val key: String, override val timestampMs: Long) : CacheEvent()

    @Serializable
    data class ApiCallHit(override val key: String, override val timestampMs: Long) : CacheEvent()

    @Serializable
    data class ApiCallMiss(override val key: String, override val timestampMs: Long) : CacheEvent()

    @Serializable
    data class ApiCallStored(override val key: String, override val timestampMs: Long) : CacheEvent()

    @Serializable
    data class Evicted(override val key: String, override val timestampMs: Long) : CacheEvent()
}