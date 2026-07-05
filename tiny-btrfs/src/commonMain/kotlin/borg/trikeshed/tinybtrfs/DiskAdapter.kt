package borg.trikeshed.tinybtrfs

/**
 * DiskAdapter: platform-specific backing storage for BPlusTree nodes.
 *
 * CommonMain-only protocol: implementations live in platform modules (jvmMain/posixMain).
 *
 * Node identifiers are Strings to allow backend-specific ids (file names, block ids).
 */
interface DiskAdapter {
    /** Read bytes for node id, or null if not present. */
    fun readNode(nodeId: String): ByteArray?

    /** Write node bytes. */
    fun writeNode(nodeId: String, bytes: ByteArray)

    /** Allocate a new node id (persistent store should guarantee uniqueness). */
    fun allocateNode(): String

    /** Free node id. */
    fun freeNode(nodeId: String)
}

/**
 * Simple in-memory DiskAdapter implementation useful for tests and platforms that
 * want a trivial memory-backed store.
 */
class InMemoryDiskAdapter : DiskAdapter {
   val store = mutableMapOf<String, ByteArray>()
   var nextId = 1L
    override fun readNode(nodeId: String): ByteArray? = store[nodeId]
    override fun writeNode(nodeId: String, bytes: ByteArray) { store[nodeId] = bytes }
    override fun allocateNode(): String {
        val id = "node-${nextId++}"
        store[id] = ByteArray(0)
        return id
    }
    override fun freeNode(nodeId: String) { store.remove(nodeId) }
}
