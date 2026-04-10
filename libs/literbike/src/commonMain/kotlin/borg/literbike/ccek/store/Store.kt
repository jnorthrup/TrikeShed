package borg.literbike.ccek.store

import borg.literbike.ccek.store.cas.ContentHashWrapper
import java.security.MessageDigest

/**
 * CCEK Store - Storage layer
 *
 * Provides content-addressed storage, CAS gateway, and backend adapters.
 * Based on original CAS implementation from src/cas_storage.rs, src/cas_gateway.rs, src/cas_backends.rs.
 */

// Re-exports for convenience
typealias Series<T> = List<T>

// ============================================================================
// Errors
// ============================================================================

/** Storage error types */
sealed class StoreError : Exception() {
    data class BlockNotFound(val hash: String) : StoreError()
    data class ObjectNotFound(val key: String) : StoreError()
    data class BackendError(val message: String) : StoreError()
    data class InvalidHash(val hash: String) : StoreError()
    data class IoError(val message: String) : StoreError()
    data class SerializationError(val message: String) : StoreError()
    data object NotImplemented : StoreError()

    override val message: String
        get() = when (this) {
            is BlockNotFound -> "Block not found: $hash"
            is ObjectNotFound -> "Object not found: $key"
            is BackendError -> "Backend error: $message"
            is InvalidHash -> "Invalid block hash: $hash"
            is IoError -> "IO error: $message"
            is SerializationError -> "Serialization error: $message"
            NotImplemented -> "Not implemented"
        }
}

/** Result type alias for storage operations */
typealias StoreResult<T> = Result<T>

// ============================================================================
// ObjectStore - S3/GCS/Aliyun-compatible object storage
// ============================================================================

/** Object metadata */
data class ObjectMeta(
    /** Object key/path */
    val key: String = "",
    /** Content type */
    val contentType: String? = null,
    /** Content length */
    val size: Long = 0L,
    /** Last modified timestamp */
    val lastModified: Long? = null,
    /** ETag */
    val etag: String? = null,
    /** Custom metadata */
    val custom: Map<String, String> = emptyMap(),
)

/** Object with data */
data class Object(
    /** Object metadata */
    val meta: ObjectMeta,
    /** Object data */
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Object) return false
        return meta == other.meta && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = meta.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/** Object storage trait (S3/GCS/Aliyun-compatible) */
interface ObjectStore {
    /** Get an object by key */
    suspend fun getObject(key: String): StoreResult<Object>

    /** Put an object */
    suspend fun putObject(key: String, data: ByteArray, meta: ObjectMeta? = null): StoreResult<ObjectMeta>

    /** Delete an object */
    suspend fun deleteObject(key: String): StoreResult<Boolean>

    /** List objects with prefix */
    suspend fun listObjects(prefix: String? = null): StoreResult<List<ObjectMeta>>

    /** Check if object exists */
    suspend fun headObject(key: String): StoreResult<ObjectMeta?>

    /** Copy object */
    suspend fun copyObject(source: String, dest: String): StoreResult<ObjectMeta>
}

// ============================================================================
// BlockId - Content address
// ============================================================================

/** Content address (SHA-256 hash) */
@JvmInline
value class BlockId(val hash: String) {
    companion object {
        fun create(hash: String): BlockId = BlockId(hash)

        fun fromBytes(data: ByteArray): BlockId {
            val hash = computeHash(data)
            return BlockId(hash)
        }
    }

    fun toBytes(): ByteArray {
        return hexDecode(hash)
    }

    override fun toString(): String = hash
}

// ============================================================================
// Block - Storage unit
// ============================================================================

/** A storage block with data and metadata */
class Block private constructor(
    private val id: BlockId,
    private val data: ByteArray,
    private val metadata: String? = null,
) {
    companion object {
        fun create(data: ByteArray): Block {
            val id = BlockId.fromBytes(data)
            return Block(id, data)
        }

        fun withMetadata(data: ByteArray, metadata: String): Block {
            val block = create(data)
            return Block(block.id, block.data, metadata)
        }
    }

    fun id(): BlockId = id

    fun data(): ByteArray = data

    fun size(): Int = data.size

    fun metadata(): String? = metadata

    fun verify(): Boolean {
        val computed = computeHash(data)
        return computed == id.hash
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Block) return false
        return id == other.id && data.contentEquals(other.data) && metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (metadata?.hashCode() ?: 0)
        return result
    }
}

// ============================================================================
// StoreKey / StoreElement - CCEK Key/Element for storage
// ============================================================================

/** Backend type */
enum class BackendType {
    Memory,
    Git,
    Ipfs,
    S3,
    RocksDb,
}

/** Storage statistics */
data class StoreStats(
    var blocksStored: Long = 0L,
    var blocksRetrieved: Long = 0L,
    var bytesStored: Long = 0L,
    var bytesRetrieved: Long = 0L,
)

/** Store Element - state container for storage */
class StoreElement(
    private val backend: BackendType,
) {
    private val cache = mutableMapOf<ContentHashWrapper, Block>()
    private var statsValue = StoreStats()

    companion object {
        fun create(backend: BackendType): StoreElement {
            return StoreElement(backend)
        }
    }

    fun backend(): BackendType = backend

    fun cacheBlock(block: Block) {
        statsValue = statsValue.copy(
            blocksStored = statsValue.blocksStored + 1,
            bytesStored = statsValue.bytesStored + block.size().toLong(),
        )
        cache[ContentHashWrapper(block.id().toBytes())] = block
    }

    fun getCached(id: BlockId): Block? {
        return cache[ContentHashWrapper(id.toBytes())]
    }

    fun stats(): StoreStats = statsValue

    fun clearCache() {
        cache.clear()
    }
}

// ============================================================================
// BlockStore - Fundamental block storage interface
// ============================================================================

/** Fundamental block storage trait */
interface BlockStore {
    suspend fun put(data: ByteArray): StoreResult<BlockId>
    suspend fun get(id: BlockId): StoreResult<Block>
    suspend fun has(id: BlockId): StoreResult<Boolean>
    suspend fun delete(id: BlockId): StoreResult<Boolean>
    suspend fun list(): StoreResult<List<BlockId>>
    fun stats(): StoreStats
}

// ============================================================================
// Utility functions
// ============================================================================

/** Compute SHA-256 hash of data */
fun computeHash(data: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(data)
    return digest.toHex()
}

/** Verify hash matches data */
fun verifyHash(data: ByteArray, expectedHash: String): Boolean {
    return computeHash(data) == expectedHash
}

/** Split data into chunks */
fun chunkData(data: ByteArray, maxSize: Int): List<ByteArray> {
    return data.asList().chunked(maxSize).map { it.toByteArray() }
}

/** Convert ByteArray to hex string */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Decode hex string to ByteArray */
private fun hexDecode(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
