package borg.trikeshed.userspace.btrfs

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.tinybtrfs.BPlusTree
import borg.trikeshed.tinybtrfs.DiskAdapter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BtrfsTreeRebuilder: rebuilds a sorted B+Tree from an unordered sequence of
 * (BtrfsKey, ByteArray) pairs stored via a [DiskAdapter].
 *
 * Algebraic signature:
 * - insert: (BtrfsKey, ByteArray) → Boolean   [sorted order enforced]
 * - find:   BtrfsKey → ByteArray?
 * - range:  (BtrfsKey, BtrfsKey) → Sequence<Join<BtrfsKey, ByteArray>>
 *
 * The [BtrfsItem] smart constructor accepts a Join<BtrfsKey, ByteArray> spec
 * via the universal `a j b` binary constructor.
 */
class BtrfsTreeRebuilder(
    private val diskAdapter: DiskAdapter,
) {
    private val mutex = Mutex()
    private var rootNodeId: String? = null
    private val tree = BPlusTree<BtrfsKey, ByteArray>(order = 32)

    /**
     * Insert (key, value) in sorted order.
     * @throws IllegalStateException if key < last inserted key (unsorted insertion)
     */
    suspend fun insert(key: BtrfsKey, value: ByteArray): Boolean = mutex.withLock {
        if (rootNodeId == null) {
            rootNodeId = diskAdapter.allocateNode()
        }
        val lastKey = tree.root.keys.lastOrNull()
        require(lastKey == null || key >= lastKey) {
            "BtrfsTreeRebuilder requires sorted insertion: lastKey=$lastKey, attempted key=$key"
        }
        tree.put(key, value)
        persistRoot()
        true
    }

    /** Exact lookup. Returns null if key not present. */
    suspend fun find(key: BtrfsKey): ByteArray? = mutex.withLock {
        tree.get(key)
    }

    /**
     * Range query [start, end) — returns a Sequence of Join pairs.
     * Use `for ((k j v) in rebuilder.range(...))` to destructure.
     */
    suspend fun range(
        start: BtrfsKey,
        end: BtrfsKey,
    ): Sequence<Join<BtrfsKey, ByteArray>> = sequence {
        val keys = tree.root.keys
        for (i in keys.indices) {
            val k = keys[i]
            if (k >= start && k < end) {
                val v = tree.get(k)
                if (v != null) yield(k j v)
            }
        }
    }

    private suspend fun persistRoot() {
        val id = rootNodeId ?: return
        val keys = tree.root.keys
        // Build leaf items: BtrfsItem(key j data) uses smart constructor = Join<BtrfsKey, ByteArray>
        val items = keys.map { k ->
            val data = tree.get(k) ?: ByteArray(0)
            BtrfsItem(k j data)
        }
        val leaf = BtrfsLeaf(items)
        val buf = ByteArray(BTRFS_NODE_SIZE)
        encodeLeaf(leaf, buf)
        diskAdapter.writeNode(id, buf)
    }
}
