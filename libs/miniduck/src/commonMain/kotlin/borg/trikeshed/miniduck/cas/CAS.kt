package borg.trikeshed.miniduck.cas

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.ObjectStoreAdapter
import borg.trikeshed.miniduck.ObjectStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.security.MessageDigest

/**
 * CAS (Content Addressable Storage) — the bucket/CAS.bin indirection layer.
 *
 * This sits BELOW couch/miniduck — it is the raw content-addressed blob store.
 * Couch uses CAS for WAL segments; miniduck uses CAS for block spillover.
 *
 * Architecture:
 *   couch/miniduck  →  CAS API  →  ObjectStoreAdapter (GCS/S3/OSS)  →  Cloud/FS
 *                      ↑
 *               CASIndex (CID → bucket/key mapping)
 */
class CAS(
    private val adapters: Map<ObjectStoreProvider, ObjectStoreAdapter>,
    private val bucket: String = "trikeshed-cas",
) {

    private val index = mutableMapOf<CID, CASEntry>()

    /**
     * Put raw bytes into CAS, return content ID.
     */
    suspend fun put(bytes: ByteArray, metadata: Map<String, String> = emptyMap()): CID {
        val cid = CID.of(bytes)
        if (index.containsKey(cid)) return cid // Deduplication by content

        // Try all adapters in order of preference
        var stored = false
        var lastError: Throwable? = null
        for ((provider, adapter) in adapters) {
            try {
                val key = "cas/${cid.hash}/${cid.value}"
                adapter.put(bucket, key, bytes, metadata + mapOf("cid" to cid.value, "provider" to provider.name))
                index[cid] = CASEntry(cid, provider, key, metadata, System.currentTimeMillis())
                stored = true
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!stored) throw IllegalStateException("No CAS adapter succeeded", lastError)
        return cid
    }

    /**
     * Get bytes by CID.
     */
    suspend fun get(cid: CID): ByteArray? {
        val entry = index[cid] ?: return null
        val adapter = adapters[entry.provider] ?: return null
        val result = adapter.get(bucket, entry.key)
        return result?.child?.bytes
    }

    /**
     * Check existence without loading bytes.
     */
    suspend fun exists(cid: CID): Boolean = index.containsKey(cid) || adapters.values.any { it.get(bucket, "cas/${cid.hash}/${cid.value}") != null }

    /**
     * Delete by CID.
     */
    suspend fun delete(cid: CID): Boolean {
        val entry = index.remove(cid) ?: return false
        return adapters[entry.provider]?.delete(bucket, entry.key) ?: false
    }

    /**
     * List all stored CIDs with prefix.
     */
    suspend fun list(prefix: String = ""): Series<CID> = adapters.values
        .flatMap { adapter ->
            adapter.list(bucket, "cas/$prefix/").objects
                .map { (it as ObjectStoreRowVec).metadata?.get("cid") ?: error("CAS index missing") }
                .map { CID(it) }
        }
        .toList()

    /**
     * Stream content by CID — for large blobs.
     */
    fun stream(cid: CID): Flow<ByteArray> = channelFlow {
        get(cid)?.let { chunk(it, 64 * 1024).forEach { send(it) } }
    }

    /** Retrieve adapter for a provider. */
    fun adapter(provider: ObjectStoreProvider): ObjectStoreAdapter? = adapters[provider]

    companion object {
        /** Create CAS with default adapters from providers. */
        suspend fun create(providers: List<ObjectStoreAdapter>): CAS = providers
            .associateBy({ it.provider }, { it })
            .let { CAS(it) }
    }
}

/**
 * Content ID — SHA-256 hash of content.
 */
@JvmInline
value class CID(val value: String) {
    companion object {
        fun of(bytes: ByteArray): CID = CID(
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        )
        fun of(hex: String): CID = CID(hex)
    }

    val prefix: String get() = value.substring(0, 4)
    val hash: String get() = prefix
}

/**
 * CAS index entry.
 */
data class CASEntry(
    val cid: CID,
    val provider: ObjectStoreProvider,
    val key: String,          // e.g. "cas/ab/cd/efdeadbeef..."
    val metadata: Map<String, String>,
    val storedAt: Long,
)

/**
 * CASIndex — persistent mapping CID → (provider, key).
 * Can be backed by miniduck block store or embedded DB.
 */
interface CASIndex {
    suspend fun put(entry: CASEntry)
    suspend fun get(cid: CID): CASEntry?
    suspend fun remove(cid: CID)
    suspend fun scan(prefix: String): Series<CASEntry>
}

/**
 * Redirector — the indirection layer between high-level IDs and CAS.
 * Used by couch (WAL segments) and miniduck (blocks).
 */
class Redirector(private val cas: CAS, private val index: CASIndex) {

    /** Redirect a logical ID (e.g. "wal/segment-123") to CAS content. */
    suspend fun redirect(logicalId: String): ByteArray? = index.get(CID.of(logicalId))
        ?.let { cas.get(it.cid) }

    /** Store and redirect: put bytes → get CID → map logicalId → CID. */
    suspend fun storeAndRedirect(logicalId: String, bytes: ByteArray): CID {
        val cid = cas.put(bytes)
        index.put(cid, CASEntry(cid, ObjectStoreProvider.MEMORY, "", emptyMap(), System.currentTimeMillis()))
        return cid
    }

    /** Multi-redirect for batch operations. */
    suspend fun redirectAll(logicalIds: List<String>): Map<String, ByteArray?> =
        logicalIds.associateWith { redirect(it) }

    /** Garbage collect unreferenced CAS entries. */
    suspend fun gc(referenced: Set<CID>) {
        index.scan("").filter { it.cid !in referenced }.forEach { index.remove(it.cid) }
    }
}