package borg.literbike.betanet

/**
 * CouchDB 1.7.2-like emulator for local testing
 * Features:
 * - in-memory document store (kv)
 * - attachments stored as base64 blobs
 * - simple design doc 'views' via filter functions
 * - simulated IPFS add/get (stores blobs by multihash-like key)
 * - a tiny HTTP-like Swagger JSON stub for the API surface
 * Ported from literbike/src/betanet/couchdb_emulator.rs
 */
import java.security.MessageDigest
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

data class Document(
    val id: String,
    val rev: Long,
    val content: ByteArray,
    val attachments: Map<String, ByteArray> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Document) return false
        return id == other.id && rev == other.rev && content.contentEquals(other.content) &&
                attachments.keys == other.attachments.keys &&
                attachments.all { (k, v) -> other.attachments[k]?.contentEquals(v) == true }
    }
    override fun hashCode(): Int = id.hashCode() xor rev.hashCode() xor content.contentHashCode()
}

class CouchDbEmulator {
    private data class DocEntry(
        var doc: Document,
    )

    private val store: MutableMap<String, DocEntry> = mutableMapOf()
    private val storeLock = ReentrantLock()

    private val ipfs: MutableMap<String, ByteArray> = mutableMapOf()
    private val ipfsLock = ReentrantLock()

    companion object {
        fun new(): CouchDbEmulator = CouchDbEmulator()
    }

    fun putDoc(id: String, content: ByteArray) {
        storeLock.withLock {
            val rev = store[id]?.doc?.rev?.plus(1) ?: 1L
            store[id] = DocEntry(
                Document(id = id, rev = rev, content = content.copyOf(), attachments = emptyMap())
            )
        }
    }

    fun getDoc(id: String): Document? = storeLock.withLock { store[id]?.doc }

    fun putAttachment(id: String, name: String, data: ByteArray): Result<Unit> {
        return storeLock.withLock {
            val entry = store[id]
            if (entry != null) {
                val newAttachments = entry.doc.attachments.toMutableMap()
                newAttachments[name] = data.copyOf()
                entry.doc = entry.doc.copy(
                    rev = entry.doc.rev + 1,
                    attachments = newAttachments,
                )
                Result.success(Unit)
            } else {
                Result.failure(Exception("not_found"))
            }
        }
    }

    fun getAttachment(id: String, name: String): ByteArray? = storeLock.withLock {
        store[id]?.doc?.attachments?.get(name)?.copyOf()
    }

    /** Very small view: return all docs where predicate returns true */
    inline fun viewFilter(crossinline predicate: (Document) -> Boolean): List<Document> {
        return storeLock.withLock {
            store.values.mapNotNull { if (predicate(it.doc)) it.doc else null }
        }
    }

    /** IPFS-like add: returns a fake multihash key (hex sha256) */
    fun ipfsAdd(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        val key = hash.joinToString("") { "%02x".format(it) }
        ipfsLock.withLock {
            ipfs[key] = data.copyOf()
        }
        return key
    }

    fun ipfsGet(key: String): ByteArray? = ipfsLock.withLock {
        ipfs[key]?.copyOf()
    }

    /** Minimal swagger stub describing the basic endpoints */
    companion object {
        const val SWAGGER_JSON: String = """{"info":{"title":"CouchDB Emulator","version":"1.7.2-emulated"},"paths":{"/db/{doc}":{"put":{},"get":{}},"/db/{doc}/attachments/{name}":{"put":{},"get":{}},"/ipfs/add":{"post":{}},"/ipfs/get":{"get":{}}}}"""
    }
}
