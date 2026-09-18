package borg.trikeshed.userspace.btrfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.tinybtrfs.DiskAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * UserspaceMemoryBuffer: a lifecycle-managed in-memory [DiskAdapter] for BPlusTree nodes.
 *
 * Extends [AsyncContextElement] to participate in the TrikeShed userspace element
 * lifecycle (CREATED → OPEN → DRAINING → CLOSED). Stores fixed-size memory chunks
 * keyed by String node IDs, with a free-list for chunk reuse.
 *
 * Usage:
 * ```kotlin
 * val buf = UserspaceMemoryBuffer(chunkSize = 4096)
 * buf.open()
 * val tree = BPlusTree<Long, String>(order = 32)
 * val nodeId = buf.allocateNode()
 * buf.writeNode(nodeId, serializeNode(rootNode))
 * val bytes = buf.readNode(nodeId)
 * buf.freeNode(nodeId)
 * buf.close()
 * ```
 *
 * @param chunkSize fixed size of each memory chunk in bytes (default 4096)
 */
class UserspaceMemoryBuffer(
    val chunkSize: Int = 4096,
) : AsyncContextElement(), DiskAdapter {

    override val key = Key
    companion object Key : CoroutineContext.Key<UserspaceMemoryBuffer>

    /** In-memory chunk store: nodeId → ByteArray. */
    private val store = mutableMapOf<String, ByteArray>()

    /** Monotonic ID counter for allocation. */
    private var nextId = 1L

    /** Free-list of previously allocated but freed node IDs. */
    private val freeList = ArrayDeque<String>()

    init {
        require(chunkSize > 0) { "chunkSize must be positive, got $chunkSize" }
    }

    // ── DiskAdapter ──────────────────────────────────────────────────────────

    override fun readNode(nodeId: String): ByteArray? {
        check(state.isAtLeast(ElementState.OPEN)) { "Buffer not open (state=$state)" }
        return store[nodeId]
    }

    override fun writeNode(nodeId: String, bytes: ByteArray) {
        check(state.isAtLeast(ElementState.OPEN)) { "Buffer not open (state=$state)" }
        check(bytes.size <= chunkSize) {
            "Node bytes (${bytes.size}) exceed chunk size ($chunkSize)"
        }
        store[nodeId] = bytes
    }

    override fun allocateNode(): String {
        check(state.isAtLeast(ElementState.OPEN)) { "Buffer not open (state=$state)" }
        // Reuse a freed ID if available, otherwise allocate fresh.
        val id = if (freeList.isNotEmpty()) {
            freeList.removeFirst()
        } else {
            "n-${nextId++}"
        }
        store[id] = ByteArray(0)
        return id
    }

    override fun freeNode(nodeId: String) {
        check(state.isAtLeast(ElementState.OPEN)) { "Buffer not open (state=$state)" }
        store.remove(nodeId)
        freeList.addLast(nodeId)
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    override suspend fun open() {
        super.open()
        // State is now OPEN; store and free-list are ready.
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            // Drain: clear all stored data.
            store.clear()
            freeList.clear()
            nextId = 1L
        }
        super.close()
    }

    /** Number of chunks currently allocated (active nodes). */
    fun chunkCount(): Int = store.size

    /** Snapshot active node bytes for read-only rebuild/inspection passes. */
    fun nodeSnapshot(): List<Pair<String, ByteArray>> =
        store.entries.map { it.key to it.value.copyOf() }

    /** Number of freed chunk IDs available for reuse. */
    fun freeCount(): Int = freeList.size

    /** Total chunks ever allocated (including freed). */
    fun totalAllocated(): Long = nextId - 1
}
