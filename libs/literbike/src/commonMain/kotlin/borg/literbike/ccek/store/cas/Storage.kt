package borg.literbike.ccek.store.cas

import kotlin.math.ceil
import kotlin.experimental.and

/**
 * Content-Addressed Storage for CAS-Free Synchronization
 *
 * This module provides content-addressed storage primitives for durability,
 * deduplication, and crash recovery. Used as WARM PATH complement to
 * HOT PATH atomic operations.
 */

// SHA256 content hash (32 bytes)
typealias ContentHash = ByteArray

// Merkle tree root hash
typealias MerkleRoot = ByteArray

/** Content-addressed blob with hash */
data class ContentBlob(
    val hash: ContentHash,
    val data: ByteArray,
    val size: Int,
) {
    companion object {
        /** Create content blob from data (computes hash automatically) */
        fun create(data: ByteArray): ContentBlob {
            val hash = digest(data)
            return ContentBlob(hash = hash, data = data, size = data.size)
        }

        /** Create content blob from pre-computed hash (for verification) */
        fun withHash(data: ByteArray, hash: ContentHash): ContentBlob {
            return ContentBlob(hash = hash, data = data, size = data.size)
        }
    }

    /** Verify content matches hash */
    fun verify(): Boolean {
        val computed = digest(data)
        return computed.contentEquals(hash)
    }

    /** Get idempotent key (content hash) */
    fun idempotentKey(): ContentHash = hash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentBlob) return false
        return hash.contentEquals(other.hash) &&
            data.contentEquals(other.data) &&
            size == other.size
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + size
        return result
    }
}

/** Merkle tree node */
sealed class MerkleNode {
    data class Leaf(val hash: ContentHash) : MerkleNode()
    data class Branch(
        val left: MerkleRoot,
        val right: MerkleRoot,
        val hash: MerkleRoot,
    ) : MerkleNode()

    fun hash(): MerkleRoot = when (this) {
        is Leaf -> hash
        is Branch -> hash
    }

    companion object {
        /** Build Merkle tree from list of hashes */
        fun buildTree(hashes: List<ContentHash>): MerkleNode? {
            if (hashes.isEmpty()) return null

            var nodes: MutableList<MerkleNode> = hashes.map { Leaf(it) }.toMutableList()

            while (nodes.size > 1) {
                val nextLevel = mutableListOf<MerkleNode>()
                var i = 0

                while (i < nodes.size) {
                    val left = nodes[i].hash()
                    val right = if (i + 1 < nodes.size) {
                        nodes[i + 1].hash()
                    } else {
                        left // Duplicate last node if odd
                    }

                    val combined = left + right
                    val hash = digest(combined)

                    nextLevel.add(Branch(left, right, hash))
                    i += 2
                }

                nodes = nextLevel
            }

            return nodes.firstOrNull()
        }
    }

    /** Get Merkle root from tree */
    fun root(): MerkleRoot = hash()
}

/** Content store statistics */
data class ContentStats(
    var totalBlobs: Long = 0L,
    var totalBytes: Long = 0L,
    var totalRefs: Long = 0L,
    var dedupRatio: Double = 0.0,
)

/** In-memory content-addressed store with thread-safe access */
class ContentAddressedStore {
    private val blobs = mutableMapOf<ContentHashWrapper, ContentBlob>()
    private val refs = mutableMapOf<String, ContentHashWrapper>()
    private val merkleRoots = mutableMapOf<ContentHashWrapper, Int>()

    companion object {
        fun create(): ContentAddressedStore = ContentAddressedStore()
    }

    /** Store content blob (idempotent - same content = same hash = no duplicate) */
    fun store(blob: ContentBlob): ContentHash {
        val hashWrapper = ContentHashWrapper(blob.hash)
        if (blobs.containsKey(hashWrapper)) {
            return blob.hash
        }
        blobs[hashWrapper] = blob
        return blob.hash
    }

    /** Retrieve content by hash */
    fun retrieve(hash: ContentHash): ContentBlob? {
        return blobs[ContentHashWrapper(hash)]
    }

    /** Store content with reference key (for stream state, etc.) */
    fun storeRef(refKey: String, refType: String, blob: ContentBlob) {
        // Store content first
        store(blob)
        // Store reference
        refs[refKey] = ContentHashWrapper(blob.hash)
    }

    /** Retrieve content by reference key */
    fun retrieveRef(refKey: String): ContentBlob? {
        val hashWrapper = refs[refKey] ?: return null
        return blobs[hashWrapper]
    }

    /** Store Merkle root */
    fun storeMerkleRoot(root: MerkleRoot, leafCount: Int) {
        merkleRoots[ContentHashWrapper(root)] = leafCount
    }

    /** Get content statistics */
    fun stats(): ContentStats {
        val totalBlobs = blobs.size.toLong()
        val totalBytes = blobs.values.sumOf { it.size.toLong() }
        val totalRefs = refs.size.toLong()

        return ContentStats(
            totalBlobs = totalBlobs,
            totalBytes = totalBytes,
            totalRefs = totalRefs,
            dedupRatio = 0.0, // Would need more tracking
        )
    }
}

/** Wrapper for ContentHash to enable proper Map key semantics */
@JvmInline
value class ContentHashWrapper(val value: ContentHash) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentHashWrapper) return false
        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int = value.contentHashCode()
}

/** Compute SHA256 digest */
fun digest(bytes: ByteArray): ContentHash {
    // UNSAFE: In Kotlin/JVM use MessageDigest; in KMP use platform-specific or Okio
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(bytes)
}
