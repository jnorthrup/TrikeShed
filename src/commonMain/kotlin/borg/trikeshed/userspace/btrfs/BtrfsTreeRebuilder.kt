package borg.trikeshed.userspace.btrfs

import borg.trikeshed.tinybtrfs.BPlusTree
import borg.trikeshed.tinybtrfs.DiskAdapter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BtrfsTreeRebuilder: rebuilds a sorted B+Tree from an unordered sequence of
 * (BtrfsKey, ByteArray) pairs stored via a DiskAdapter.
 *
 * Wraps a BPlusTree and provides:
 * - Sorted invariant enforcement on insert
 * - Persistence of root node via DiskAdapter
 * - Range queries
 */
class BtrfsTreeRebuilder(
    private val diskAdapter: DiskAdapter
) {
    private val mutex = Mutex()
    private var rootNodeId: String? = null
    private val tree = BPlusTree<BtrfsKey, ByteArray>(order = 32)

    /** Insert a key/value pair. Throws if keys are not in sorted order. */
    suspend fun insert(key: BtrfsKey, value: ByteArray): Boolean {
        mutex.withLock {
            if (rootNodeId == null) {
                rootNodeId = diskAdapter.allocateNode()
            }
            // Sorted invariant: new key must be >= last key
            val allKeys = tree.root.keys
            val lastKey = if (allKeys.isNotEmpty()) allKeys[allKeys.size - 1] else null
            require(lastKey == null || key >= lastKey) {
                "Items must be inserted in sorted order: lastKey=$lastKey, key=$key"
            }
            tree.put(key, value)
            persistRoot()
            return true
        }
    }

    /** Find a value by key. Returns null if not found. */
    suspend fun find(key: BtrfsKey): ByteArray? {
        mutex.withLock {
            return tree.get(key)
        }
    }

    /** Range query: all (key, value) where start <= key < end. */
    suspend fun range(start: BtrfsKey, end: BtrfsKey): Sequence<Pair<BtrfsKey, ByteArray>> {
        return sequence {
            val allKeys = tree.root.keys
            for (i in allKeys.indices) {
                val k = allKeys[i]
                if (k >= start && k < end) {
                    val v = tree.get(k)
                    if (v != null) yield(Pair(k, v))
                }
            }
        }
    }

    private suspend fun persistRoot() {
        val rootId = rootNodeId ?: return
        val allKeys = tree.root.keys
        val items = mutableListOf<BtrfsItem>()
        for (i in allKeys.indices) {
            val k = allKeys[i]
            val v = tree.get(k)
            items.add(BtrfsItem(k, 0u, v?.size?.toUInt() ?: 0u, v ?: ByteArray(0)))
        }
        val leafNode = BtrfsLeaf(items)
        val buf = ByteArray(4096)
        encodeLeaf(leafNode, buf)
        diskAdapter.writeNode(rootId, buf)
    }
}
