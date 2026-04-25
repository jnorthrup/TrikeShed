package borg.trikeshed.tinybtrfs

/**
 * Tiny B+Tree skeleton (commonMain)
 * - Minimal in-memory B+tree API to be reused by a userspace btrfs implementation.
 * - CommonMain-only: no platform I/O or native bindings here. Persistence/backing
 *   storage should be provided by a platform-specific adapter.
 *
 * This is intentionally minimal to maximize reuse of existing TrikeShed
 * algebra/types and to provide a small, portable starting point.
 */
class BPlusTree<K : Comparable<K>, V>(private val order: Int = 32) {
    init { require(order >= 3) { "order must be >= 3" } }

    private sealed class Node {
        abstract val keys: MutableList<K>
        abstract fun isLeaf(): Boolean
    }

    private inner class LeafNode : Node() {
        override val keys = mutableListOf<K>()
        val values = mutableListOf<V?>()
        var next: LeafNode? = null
        override fun isLeaf() = true
    }

    private inner class InternalNode : Node() {
        override val keys = mutableListOf<K>()
        val children = mutableListOf<Node>()
        override fun isLeaf() = false
    }

    private var root: Node = LeafNode()
    private var _size = 0

    /** Insert or replace a key/value. */
    fun put(key: K, value: V) {
        val existed = get(key) != null
        val split = insert(root, key, value)
        if (split != null) {
            val (left, pivot, right) = split
            val newRoot = InternalNode()
            newRoot.keys.add(pivot)
            newRoot.children.add(left)
            newRoot.children.add(right)
            root = newRoot
        }
        if (!existed) _size++
    }

    /** Lookup value by key. */
    fun get(key: K): V? {
        val leaf = findLeaf(root, key) as LeafNode
        val idx = leaf.keys.binarySearch(key)
        return if (idx >= 0) leaf.values[idx] else null
    }

    fun size(): Int = _size

    /** Internal insert. Returns Triple(left, pivot, right) when a split occurs. */
    private fun insert(node: Node, key: K, value: V): Triple<Node, K, Node>? {
        if (node.isLeaf()) {
            val leaf = node as LeafNode
            val idx = leaf.keys.binarySearch(key)
            if (idx >= 0) {
                leaf.values[idx] = value
                return null
            }
            val insertAt = -idx - 1
            leaf.keys.add(insertAt, key)
            leaf.values.add(insertAt, value)
            if (leaf.keys.size > order) {
                val mid = leaf.keys.size / 2
                val right = LeafNode()
                right.keys.addAll(leaf.keys.subList(mid, leaf.keys.size))
                right.values.addAll(leaf.values.subList(mid, leaf.values.size))
                val leftKeys = leaf.keys.subList(0, mid).toList()
                val leftVals = leaf.values.subList(0, mid).toList()
                leaf.keys.clear(); leaf.keys.addAll(leftKeys)
                leaf.values.clear(); leaf.values.addAll(leftVals)
                right.next = leaf.next
                leaf.next = right
                val pivot = right.keys.first()
                return Triple(leaf, pivot, right)
            }
            return null
        } else {
            val internal = node as InternalNode
            val idx = internal.keys.binarySearch(key)
            val childIndex = if (idx >= 0) idx + 1 else -idx - 1
            val child = internal.children[childIndex]
            val split = insert(child, key, value)
            if (split != null) {
                val (left, pivot, right) = split
                // find child position and replace
                val replaceIndex = internal.children.indexOf(child)
                if (replaceIndex >= 0) {
                    internal.children[replaceIndex] = left
                    internal.children.add(replaceIndex + 1, right)
                    internal.keys.add(replaceIndex, pivot)
                } else {
                    // fallback append
                    internal.children.add(right)
                    internal.keys.add(pivot)
                }
                if (internal.children.size > order) {
                    val mid = internal.keys.size / 2
                    val pivotKey = internal.keys[mid]
                    val rightNode = InternalNode()
                    rightNode.keys.addAll(internal.keys.subList(mid + 1, internal.keys.size))
                    rightNode.children.addAll(internal.children.subList(mid + 1, internal.children.size))
                    val leftKeys = internal.keys.subList(0, mid).toList()
                    val leftChildren = internal.children.subList(0, mid + 1).toList()
                    internal.keys.clear(); internal.keys.addAll(leftKeys)
                    internal.children.clear(); internal.children.addAll(leftChildren)
                    return Triple(internal, pivotKey, rightNode)
                }
            }
            return null
        }
    }

    private fun findLeaf(node: Node, key: K): Node {
        var cur = node
        while (!cur.isLeaf()) {
            val internal = cur as InternalNode
            val idx = internal.keys.binarySearch(key)
            var childIndex = if (idx >= 0) idx + 1 else -idx - 1
            if (childIndex < 0) childIndex = 0
            if (childIndex >= internal.children.size) childIndex = internal.children.size - 1
            cur = internal.children[childIndex]
        }
        return cur
    }
}
