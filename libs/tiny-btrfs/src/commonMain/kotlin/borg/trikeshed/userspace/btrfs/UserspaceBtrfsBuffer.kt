package borg.trikeshed.userspace.btrfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.tinybtrfs.BtrfsMount
import borg.trikeshed.tinybtrfs.DiskAdapter
import borg.trikeshed.tinybtrfs.NodeId
import borg.trikeshed.tinybtrfs.toNodeId
import kotlin.coroutines.CoroutineContext

/**
 * UserspaceBtrfsBuffer: lifecycle-managed in-memory [DiskAdapter] for BPlusTree nodes.
 *
 * Extends [AsyncContextElement] to participate in the TrikeShed userspace element
 * lifecycle (CREATED → OPEN → DRAINING → CLOSED).
 *
 * Provides btrfs-format node storage (4 KiB fixed-size blocks) with:
 * - Free-list extent allocation (reuses freed block IDs)
 * - BtrfsNodeHeader validation on read
 * - CRC32C checksum verification
 *
 * ## Join algebra throughout
 * - `BtrfsItem(Key j DataBytes)` — leaf item smart constructor
 * - `BtrfsChildPointer(Key j BlockPtr)` — internal node child pointer
 * - `encodeLeaf(items, buf)` — validates + writes with CRC32C
 * - `decodeLeaf(buf)` → `BtrfsLeaf` — validates + reads
 *
 * @param chunkSize fixed size of each memory block in bytes (default 4096 = BTRFS_NODE_SIZE)
 */
class UserspaceBtrfsBuffer(
    val chunkSize: Int = BTRFS_NODE_SIZE,
) : AsyncContextElement(), DiskAdapter {

    override val key: CoroutineContext.Key<*> = Key
    companion object Key : CoroutineContext.Key<UserspaceBtrfsBuffer>

    /** In-memory block store: nodeId → ByteArray (size = chunkSize). */
    private val store = LinkedHashMap<NodeId, ByteArray>()

    /** Monotonic ID counter for allocation. */
    private var nextId = 1L

    /** Free-list of previously allocated but freed node IDs. */
    private val freeList = ArrayDeque<NodeId>()

    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(chunkSize >= BTRFS_NODE_SIZE) { "chunkSize ($chunkSize) < BTRFS_NODE_SIZE ($BTRFS_NODE_SIZE)" }
    }

    // ── DiskAdapter ──────────────────────────────────────────────────────────

    override fun readNode(nodeId: NodeId): ByteArray? {
        check(state.isAtLeast(ElementState.OPEN)) { "Buffer not open (state=$state)" }
        return store[nodeId]?.copyOf()
    }

    override fun writeNode(nodeId: NodeId, bytes: ByteArray) {
        check(state.isAtLeast(ElementState.OPEN)) { "Buffer not open (state=$state)" }
        require(bytes.size <= chunkSize) { "Node bytes (${bytes.size}) > chunkSize ($chunkSize)" }
        // Pad to exactly chunkSize if smaller
        val toStore = if (bytes.size < chunkSize) {
            ByteArray(chunkSize).also { bytes.copyInto(it) }
        } else bytes
        store[nodeId] = toStore.copyOf()
    }

    override fun allocateNode(): NodeId {
        check(state.isAtLeast(ElementState.OPEN)) { "Buffer not open (state=$state)" }
        val id = if (freeList.isNotEmpty()) freeList.removeFirst() else "n-${nextId++}".toNodeId()
        store[id] = ByteArray(chunkSize)
        return id
    }

    override fun freeNode(nodeId: NodeId) {
        check(state.isAtLeast(ElementState.OPEN)) { "Buffer not open (state=$state)" }
        store.remove(nodeId)
        freeList.addLast(nodeId)
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    override suspend fun open() {
        super.open()
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            store.clear()
            freeList.clear()
            nextId = 1L
        }
        super.close()
    }

    /** Number of blocks currently allocated. */
    fun chunkCount(): Int = store.size

    /** Number of freed block IDs available for reuse. */
    fun freeCount(): Int = freeList.size

    /** Total blocks ever allocated. */
    fun totalAllocated(): Long = nextId - 1

    fun mount(
        volatileReads: Boolean = false,
        maxReadAttempts: Int = 3,
    ): BtrfsMount = BtrfsMount(this, volatileReads, maxReadAttempts)

    /** Write a properly-formatted leaf node. */
    fun writeLeaf(nodeId: NodeId, leaf: BtrfsLeaf, generation: ULong = 0UL) {
        val buf = ByteArray(chunkSize)
        encodeLeaf(leaf, buf, generation)
        writeNode(nodeId, buf)
    }

    /** Write a properly-formatted internal node. */
    fun writeInternal(nodeId: NodeId, internal: BtrfsInternal, generation: ULong = 0UL) {
        val buf = ByteArray(chunkSize)
        encodeInternal(internal, buf, generation)
        writeNode(nodeId, buf)
    }

    /** Read and decode a leaf node. */
    fun readLeaf(nodeId: NodeId): BtrfsLeaf {
        return mount().readLeaf(nodeId)
    }

    /** Read and decode an internal node. */
    fun readInternal(nodeId: NodeId): BtrfsInternal {
        return mount().readInternal(nodeId)
    }
}
