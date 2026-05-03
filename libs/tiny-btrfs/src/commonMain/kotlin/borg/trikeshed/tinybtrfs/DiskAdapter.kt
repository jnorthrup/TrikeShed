package borg.trikeshed.tinybtrfs

import borg.trikeshed.lib.*

/**
 * DiskAdapter: platform-specific backing storage for BPlusTree nodes.
 *
 * CommonMain-only protocol: implementations live in platform modules (jvmMain/posixMain).
 *
 * Node identifiers are Series<Char>-backed values so VFS-facing code stays on the
 * project char-series algebra instead of intern-prone String surfaces.
 */
class NodeId(val chars: Series<Char>) : Comparable<NodeId> {
    override fun compareTo(other: NodeId): Int {
        val limit = minOf(chars.size, other.chars.size)
        for (index in 0 until limit) {
            val cmp = chars[index].compareTo(other.chars[index])
            if (cmp != 0) return cmp
        }
        return chars.size.compareTo(other.chars.size)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeId) return false
        if (chars.size != other.chars.size) return false
        for (index in 0 until chars.size) {
            if (chars[index] != other.chars[index]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = 1
        for (index in 0 until chars.size) {
            result = 31 * result + chars[index].hashCode()
        }
        return result
    }

    fun encodeToByteArray(): ByteArray = chars.encodeToByteArray()

    override fun toString(): String = chars.asString()

    companion object {
        fun fromBytes(bytes: ByteArray): NodeId = NodeId(bytes.decodeToChars())
    }
}

fun String.toNodeId(): NodeId = NodeId(toSeries())
fun Series<Char>.toNodeId(): NodeId = NodeId(this)

interface DiskAdapter {
    /**
     * Read bytes for node id, or null if not present.
     *
     * Implementations should return a stable snapshot. Adapters backed by live
     * mutable memory should copy before returning.
     */
    fun readNode(nodeId: NodeId): ByteArray?

    /** Write node bytes. */
    fun writeNode(nodeId: NodeId, bytes: ByteArray)

    /** Allocate a new node id (persistent store should guarantee uniqueness). */
    fun allocateNode(): NodeId

    /** Free node id. */
    fun freeNode(nodeId: NodeId)
}

/** Marker for adapters backed by storage that can change while a read is in flight. */
interface VolatileDiskAdapter : DiskAdapter

data class BtrfsMount(
    val disk: DiskAdapter,
    val volatileReads: Boolean = disk is VolatileDiskAdapter,
    val maxReadAttempts: Int = 3,
) {
    init {
        require(maxReadAttempts > 0) { "maxReadAttempts must be positive" }
    }
}

fun DiskAdapter.mount(
    volatileReads: Boolean = this is VolatileDiskAdapter,
    maxReadAttempts: Int = 3,
): BtrfsMount = BtrfsMount(this, volatileReads, maxReadAttempts)

enum class NodeReadStability {
    Stable,
    Volatile,
}

/**
 * Read one node image across the storage/purity boundary.
 *
 * `Stable` makes a defensive copy of the adapter result.
 * `Volatile` is for mmap/shared storage we do not own: it reads two copied
 * images, verifies both, and only returns when two consecutive images match.
 */
fun DiskAdapter.readNodeImage(
    nodeId: NodeId,
    stability: NodeReadStability = if (this is VolatileDiskAdapter) NodeReadStability.Volatile else NodeReadStability.Stable,
    maxAttempts: Int = 3,
    verify: (ByteArray) -> Unit = {},
): ByteArray? {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    return when (stability) {
        NodeReadStability.Stable -> readStableImage({ readNode(nodeId) }, verify)
        NodeReadStability.Volatile -> readVolatileImage("node $nodeId", maxAttempts, { readNode(nodeId) }, verify)
    }
}

fun BtrfsMount.readNodeImage(
    nodeId: NodeId,
    verify: (ByteArray) -> Unit = {},
): ByteArray? {
    val stability = if (volatileReads) NodeReadStability.Volatile else NodeReadStability.Stable
    return disk.readNodeImage(nodeId, stability, maxReadAttempts, verify)
}

fun readStableImage(
    read: () -> ByteArray?,
    verify: (ByteArray) -> Unit = {},
): ByteArray? = read()?.copyOf()?.also(verify)

fun readVolatileImage(
    label: String,
    maxAttempts: Int,
    read: () -> ByteArray?,
    verify: (ByteArray) -> Unit,
): ByteArray? {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    var lastFailure: Throwable? = null
    repeat(maxAttempts) {
        val first = read()?.copyOf() ?: return null
        val second = read()?.copyOf() ?: return null
        try {
            verify(first)
            verify(second)
            if (first.contentEquals(second)) return first
        } catch (t: Throwable) {
            lastFailure = t
        }
    }
    throw IllegalStateException("Volatile $label did not produce a stable image after $maxAttempts attempts", lastFailure)
}

/**
 * Simple in-memory DiskAdapter implementation useful for tests and platforms that
 * want a trivial memory-backed store.
 */
class InMemoryDiskAdapter : DiskAdapter {
   val store = mutableMapOf<NodeId, ByteArray>()
   var nextId = 1L
    override fun readNode(nodeId: NodeId): ByteArray? = store[nodeId]?.copyOf()
    override fun writeNode(nodeId: NodeId, bytes: ByteArray) { store[nodeId] = bytes.copyOf() }
    override fun allocateNode(): NodeId {
        val id = "node-${nextId++}".toNodeId()
        store[id] = ByteArray(0)
        return id
    }
    override fun freeNode(nodeId: NodeId) { store.remove(nodeId) }
}
