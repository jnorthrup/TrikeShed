package borg.trikeshed.tinybtrfs

/**
 * DiskAdapter: platform-specific backing storage for BPlusTree nodes.
 *
 * CommonMain-only protocol: implementations live in platform modules (jvmMain/posixMain).
 */
interface DiskAdapter {
    /** Read bytes for node id, or null if not present. */
    fun readNode(nodeId: Long): ByteArray?

    /** Write node bytes. */
    fun writeNode(nodeId: Long, bytes: ByteArray)

    /** Allocate a new node id (persistent store should guarantee uniqueness). */
    fun allocateNode(): Long

    /** Free node id. */
    fun freeNode(nodeId: Long)
}

/**
 * Simple in-memory DiskAdapter implementation useful for tests and platforms that
 * want a trivial memory-backed store.
 */
class InMemoryDiskAdapter : DiskAdapter {
    private val store = mutableMapOf<Long, ByteArray>()
    private var nextId = 1L
    override fun readNode(nodeId: Long): ByteArray? = store[nodeId]
    override fun writeNode(nodeId: Long, bytes: ByteArray) { store[nodeId] = bytes }
    override fun allocateNode(): Long = nextId++
    override fun freeNode(nodeId: Long) { store.remove(nodeId) }
}
