package borg.trikeshed.forge

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val forgePersistenceJson = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private const val WAL_ROTATION_LIMIT = 100

@Serializable
data class ForgeWalEntry(
    val id: String,
    val kind: String,
    val label: String = "",
    val source: String = "forge",
    val timestampMs: Long = 0L,
    val snapshot: JsonObject,
)

@Serializable
data class ForgePersistenceEnvelope(
    val version: Int = 2,
    val source: String = "localStorage",
    val snapshot: JsonObject,
    val wal: List<ForgeWalEntry> = emptyList(),
    val warning: String = "",
    val lastPersistedAtMs: Long = 0L,
)

fun encodePersistenceEnvelope(envelope: ForgePersistenceEnvelope): String = forgePersistenceJson.encodeToString(ForgePersistenceEnvelope.serializer(), envelope)

fun decodePersistenceEnvelope(raw: String?): ForgePersistenceEnvelope? {
    if (raw.isNullOrBlank()) return null
    return try {
        val parsed = forgePersistenceJson.parseToJsonElement(raw)
        when (parsed) {
            is JsonObject -> {
                val snapshot = parsed["snapshot"]
                if (snapshot is JsonObject) {
                    val wal = parsed["wal"]?.let { walElement ->
                        walElement.jsonArray.mapNotNull { entry ->
                            val entryObject = entry.jsonObject
                            val entrySnapshot = entryObject["snapshot"] as? JsonObject ?: return@mapNotNull null
                            ForgeWalEntry(
                                id = entryObject["id"]?.jsonPrimitive?.contentOrNull ?: "evt",
                                kind = entryObject["kind"]?.jsonPrimitive?.contentOrNull ?: "state-change",
                                label = entryObject["label"]?.jsonPrimitive?.contentOrNull ?: "",
                                source = entryObject["source"]?.jsonPrimitive?.contentOrNull ?: "forge",
                                timestampMs = entryObject["timestampMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                                snapshot = entrySnapshot,
                            )
                        }
                    } ?: emptyList()
                    ForgePersistenceEnvelope(
                        version = parsed["version"]?.jsonPrimitive?.intOrNull ?: 2,
                        source = parsed["source"]?.jsonPrimitive?.contentOrNull ?: "localStorage",
                        snapshot = snapshot,
                        wal = wal,
                        warning = parsed["warning"]?.jsonPrimitive?.contentOrNull ?: "",
                        lastPersistedAtMs = parsed["lastPersistedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                } else {
                    ForgePersistenceEnvelope(
                        version = parsed["version"]?.jsonPrimitive?.intOrNull ?: 1,
                        source = parsed["source"]?.jsonPrimitive?.contentOrNull ?: "localStorage",
                        snapshot = parsed,
                    )
                }
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}

fun appendWal(raw: String?, snapshot: JsonObject, mutation: ForgeWalEntry): String {
    val current = decodePersistenceEnvelope(raw) ?: ForgePersistenceEnvelope(snapshot = snapshot)
    val nextWal = (current.wal + mutation.copy(snapshot = snapshot)).takeLast(WAL_ROTATION_LIMIT + 1)
    val checkpoint = if (nextWal.size > WAL_ROTATION_LIMIT) nextWal[WAL_ROTATION_LIMIT - 1].snapshot else current.snapshot
    val rotatedWal = if (nextWal.size > WAL_ROTATION_LIMIT) nextWal.drop(WAL_ROTATION_LIMIT) else nextWal
    return encodePersistenceEnvelope(
        current.copy(
            snapshot = checkpoint,
            wal = rotatedWal,
            warning = current.warning,
            lastPersistedAtMs = current.lastPersistedAtMs,
        ),
    )
}

fun rebuildSnapshot(raw: String?, seed: JsonObject): JsonObject {
    val envelope = decodePersistenceEnvelope(raw) ?: return seed
    return envelope.wal.lastOrNull()?.snapshot ?: envelope.snapshot
}

fun fallbackWarning(): String = "IndexedDB unavailable; falling back to localStorage"

interface ForgePersistenceStore {
    fun readLocalStorage(): String?
    fun writeLocalStorage(value: String)
    suspend fun readIndexedDb(): String?
    suspend fun writeIndexedDb(value: String)
    suspend fun writeCache(value: String)
    suspend fun clear()
}

class InMemoryForgePersistenceStore(
    initialLocalStorage: String? = null,
    initialIndexedDb: String? = null,
    initialCache: String? = null,
    var failIndexedDb: Boolean = false,
) : ForgePersistenceStore {
    private var localStorageValue: String? = initialLocalStorage
    private var indexedDbValue: String? = initialIndexedDb
    private var cacheValue: String? = initialCache

    override fun readLocalStorage(): String? = localStorageValue
    override fun writeLocalStorage(value: String) {
        localStorageValue = value
    }

    override suspend fun readIndexedDb(): String? {
        if (failIndexedDb) error("IndexedDB unavailable")
        return indexedDbValue
    }

    override suspend fun writeIndexedDb(value: String) {
        if (failIndexedDb) error("IndexedDB unavailable")
        indexedDbValue = value
    }

    override suspend fun writeCache(value: String) {
        cacheValue = value
    }

    override suspend fun clear() {
        localStorageValue = null
        indexedDbValue = null
        cacheValue = null
    }

    fun snapshot(): Triple<String?, String?, String?> = Triple(localStorageValue, indexedDbValue, cacheValue)
}

class ForgePersistenceCoordinator(private val store: ForgePersistenceStore) {
    var mode: String = "Dual"
        private set
    var warning: String = ""
        private set
    private var rotationSnapshot: JsonObject? = null

    fun recordMutation(state: JsonObject, mutation: ForgeWalEntry): Int {
        val current = decodePersistenceEnvelope(store.readLocalStorage()) ?: ForgePersistenceEnvelope(snapshot = state)
        val entry = mutation.copy(snapshot = state)
        val nextWal = current.wal + entry
        val nextEnvelope = if (nextWal.size > WAL_ROTATION_LIMIT) {
            rotationSnapshot = nextWal[WAL_ROTATION_LIMIT - 1].snapshot
            current.copy(snapshot = rotationSnapshot ?: state, wal = nextWal.drop(WAL_ROTATION_LIMIT))
        } else {
            current.copy(snapshot = state, wal = nextWal)
        }
        store.writeLocalStorage(encodePersistenceEnvelope(nextEnvelope.copy(warning = warning, lastPersistedAtMs = nextEnvelope.lastPersistedAtMs)))
        return nextWal.size
    }

    suspend fun persistSnapshot(state: JsonObject? = null): ForgePersistenceEnvelope {
        val latest = decodePersistenceEnvelope(store.readLocalStorage())
        val snapshot = state ?: rotationSnapshot ?: latest?.snapshot ?: JsonObject(emptyMap())
        val wal = latest?.wal ?: emptyList()
        val envelope = ForgePersistenceEnvelope(
            snapshot = snapshot,
            wal = wal,
            warning = warning,
            lastPersistedAtMs = latest?.lastPersistedAtMs ?: 0L,
            source = "localStorage",
            version = latest?.version ?: 2,
        )
        return try {
            val serialized = encodePersistenceEnvelope(envelope.copy(source = "indexeddb"))
            store.writeIndexedDb(serialized)
            store.writeCache(serialized)
            mode = "Dual"
            warning = ""
            envelope
        } catch (_: Throwable) {
            mode = "LocalStorageOnly"
            warning = fallbackWarning()
            envelope.copy(warning = warning)
        }.also { persisted ->
            rotationSnapshot = null
            store.writeLocalStorage(encodePersistenceEnvelope(persisted.copy(source = "localStorage")))
        }
    }

    suspend fun loadLatest(seed: JsonObject): JsonObject {
        val local = decodePersistenceEnvelope(store.readLocalStorage())
        val indexed = runCatching { decodePersistenceEnvelope(store.readIndexedDb()) }.getOrNull()
        val base = indexed ?: local ?: ForgePersistenceEnvelope(snapshot = seed)
        return base.wal.lastOrNull()?.snapshot ?: base.snapshot
    }

    suspend fun clear() {
        store.clear()
        rotationSnapshot = null
        warning = ""
        mode = "Dual"
    }
}
