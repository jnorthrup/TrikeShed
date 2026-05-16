@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.tinybtrfs

import borg.trikeshed.lib.*
import kotlin.jvm.JvmName

interface Codec<K : Comparable<K>, V> {
    fun encode(node: BPlusTree.Node<K, V>): ByteArray
    fun decode(bytes: ByteArray): BPlusTree.Node<K, V>
}

class BPlusTree<K : Comparable<K>, V>(
    val order: Int = 32,
    val diskAdapter: DiskAdapter? = null,
    val codec: Codec<K, V>? = null
) {
    init { require(order >= 3) { "order must be >= 3" } }

    sealed class Node<K, V> {
        abstract val keySeries: Series<K>
        abstract fun isLeaf(): Boolean
        var id: NodeId? = null
    }

    inner class LeafNode : Node<K, V>() {
        var _keys: Array<Any?> = arrayOfNulls(order + 1)
        var _values: Array<Any?> = arrayOfNulls(order + 1)
        var keysCount: Int = 0

        override val keySeries: Series<K>
            get() = keysCount j { i: Int -> _keys[i] as K }

        val valueSeries: Series<V?>
            get() = keysCount j { i: Int -> _values[i] as V? }

        val entries: Series2<K, V?> get() = ReifiedSplitSeries2(keySeries, valueSeries)

        override fun isLeaf() = true

        fun clone(): LeafNode {
            val copy = LeafNode()
            copy.keysCount = keysCount
            for (i in 0 until keysCount) {
                copy._keys[i] = _keys[i]
                copy._values[i] = _values[i]
            }
            return copy
        }

        fun keyAt(i: Int): K {
            return _keys[i] as K
        }

        fun valueAt(i: Int): V? {
            return _values[i] as V?
        }

        fun setValueAt(i: Int, v: V): LeafNode {
            val copy = clone()
            copy._values[i] = v
            return copy
        }

        fun binarySearchKeys(key: K): Int {
            var low = 0
            var high = keysCount - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val midVal = _keys[mid] as K
                val cmp = midVal.compareTo(key)
                when {
                    cmp < 0 -> low = mid + 1
                    cmp > 0 -> high = mid - 1
                    else -> return mid
                }
            }
            return -(low + 1)
        }

        fun insertAt(index: Int, key: K, value: V): LeafNode {
            val copy = clone()
            for (i in copy.keysCount downTo index + 1) {
                copy._keys[i] = copy._keys[i - 1]
                copy._values[i] = copy._values[i - 1]
            }
            copy._keys[index] = key
            copy._values[index] = value
            copy.keysCount++
            return copy
        }

        fun removeAt(index: Int): LeafNode {
            val copy = clone()
            for (i in index until copy.keysCount - 1) {
                copy._keys[i] = copy._keys[i + 1]
                copy._values[i] = copy._values[i + 1]
            }
            copy._keys[copy.keysCount - 1] = null
            copy._values[copy.keysCount - 1] = null
            copy.keysCount--
            return copy
        }

        fun splitAt(mid: Int): Twin<LeafNode> {
            val left = LeafNode()
            val right = LeafNode()

            for (i in 0 until mid) {
                left._keys[i] = _keys[i]
                left._values[i] = _values[i]
            }
            left.keysCount = mid

            val rightLen = keysCount - mid
            for (i in 0 until rightLen) {
                right._keys[i] = _keys[mid + i]
                right._values[i] = _values[mid + i]
            }
            right.keysCount = rightLen

            return Twin(left, right)
        }
    }

    inner class InternalNode : Node<K, V>() {
        var _keys: Array<Any?> = arrayOfNulls(order + 1)
        var _children: Array<Any?> = arrayOfNulls(order + 2)
        var keysCount: Int = 0
        var childrenCount: Int = 0

        override val keySeries: Series<K>
            get() = keysCount j { i -> _keys[i] as K }

        val childSeries: Series<Node<K, V>>
            get() = childrenCount j { i -> _children[i] as Node<K, V> }

        val entries: Series2<K?, Node<K, V>> get() {
            val ks: Series<K?> = (keysCount + 1) j { i -> if (i < keysCount) keyAt(i) else null }
            return ReifiedSplitSeries2(ks, childSeries)
        }

        override fun isLeaf() = false

        fun clone(): InternalNode {
            val copy = InternalNode()
            copy.keysCount = keysCount
            copy.childrenCount = childrenCount
            for (i in 0 until keysCount) {
                copy._keys[i] = _keys[i]
            }
            for (i in 0 until childrenCount) {
                copy._children[i] = _children[i]
            }
            return copy
        }

        fun keyAt(i: Int): K {
            return _keys[i] as K
        }

        fun childAt(i: Int): Node<K, V> {
            return _children[i] as Node<K, V>
        }

        fun binarySearchKeys(key: K): Int {
            var low = 0
            var high = keysCount - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val midVal = _keys[mid] as K
                val cmp = midVal.compareTo(key)
                when {
                    cmp < 0 -> low = mid + 1
                    cmp > 0 -> high = mid - 1
                    else -> return mid
                }
            }
            return -(low + 1)
        }

        fun insertKeyAt(index: Int, key: K): InternalNode {
            val copy = clone()
            for (i in copy.keysCount downTo index + 1) {
                copy._keys[i] = copy._keys[i - 1]
            }
            copy._keys[index] = key
            copy.keysCount++
            return copy
        }

        fun setChildAt(index: Int, child: Node<K, V>): InternalNode {
            val copy = clone()
            copy._children[index] = child
            return copy
        }

        fun setKeyAt(index: Int, key: K): InternalNode {
            val copy = clone()
            copy._keys[index] = key
            return copy
        }

        fun insertChildAt(index: Int, child: Node<K, V>): InternalNode {
            val copy = clone()
            for (i in copy.childrenCount downTo index + 1) {
                copy._children[i] = copy._children[i - 1]
            }
            copy._children[index] = child
            copy.childrenCount++
            return copy
        }

        fun addChild(child: Node<K, V>): InternalNode {
            val copy = clone()
            copy._children[copy.childrenCount] = child
            copy.childrenCount++
            return copy
        }

        fun removeKeyAndChildAt(keyIndex: Int, childIndex: Int): InternalNode {
            val copy = clone()
            for (i in keyIndex until copy.keysCount - 1) {
                copy._keys[i] = copy._keys[i + 1]
            }
            copy._keys[copy.keysCount - 1] = null
            copy.keysCount--

            for (i in childIndex until copy.childrenCount - 1) {
                copy._children[i] = copy._children[i + 1]
            }
            copy._children[copy.childrenCount - 1] = null
            copy.childrenCount--
            return copy
        }

        fun splitAt(mid: Int): Join<K, Twin<InternalNode>> {
            val pivotKey = _keys[mid] as K

            val left = InternalNode()
            val right = InternalNode()

            for (i in 0 until mid) {
                left._keys[i] = _keys[i]
            }
            left.keysCount = mid
            for (i in 0..mid) {
                left._children[i] = _children[i]
            }
            left.childrenCount = mid + 1

            val rightKeyLen = keysCount - mid - 1
            for (i in 0 until rightKeyLen) {
                right._keys[i] = _keys[mid + 1 + i]
            }
            right.keysCount = rightKeyLen

            val rightChildLen = childrenCount - mid - 1
            for (i in 0 until rightChildLen) {
                right._children[i] = _children[mid + 1 + i]
            }
            right.childrenCount = rightChildLen

            return pivotKey j Twin(left, right)
        }
    }

    private var _size = 0

    var root: Node<K, V> = LeafNode()
        set(value) {
            field = value
            _size = countEntries(value)
        }

    init {
        _size = 0
    }

    private fun countEntries(node: Node<K, V>): Int = when (node) {
        is LeafNode -> node.keysCount
        is InternalNode -> {
            var total = 0
            for (i in 0 until node.childrenCount) {
                total += countEntries(node.childAt(i))
            }
            total
        }
    }

    fun put(key: K, value: V) {
        val result = insert(root, key, value)
        root = if (result is Join<*, *>) {
            val split = result as Join<K, Twin<Node<K, V>>>
            val pivot: K = split.a
            val twins: Twin<Node<K, V>> = split.b
            val left: Node<K, V> = twins.a
            val right: Node<K, V> = twins.b
            var newRoot = InternalNode()
            newRoot = newRoot.insertKeyAt(0, pivot)
            newRoot = newRoot.addChild(left)
            newRoot = newRoot.addChild(right)
            newRoot
        } else {
            result as Node<K, V>
        }
    }

    fun get(key: K): V? {
        val leaf: BPlusTree<K, V>.LeafNode = findLeaf(root, key) as LeafNode
        val idx = leaf.binarySearchKeys(key)
        return if (idx >= 0) leaf.valueAt(idx) else null
    }

    fun containsKey(key: K): Boolean {
        val leaf: BPlusTree<K, V>.LeafNode = findLeaf(root, key) as LeafNode
        return leaf.binarySearchKeys(key) >= 0
    }

    fun remove(key: K): Boolean {
        if (!containsKey(key)) return false

        val newRoot = delete(root, key)
        root = if (newRoot != null) {
            if (!newRoot.isLeaf() && (newRoot as InternalNode).keysCount == 0 && newRoot.childrenCount == 1) {
                newRoot.childAt(0)
            } else {
                newRoot
            }
        } else {
            LeafNode()
        }
        return true
    }

    fun size(): Int = _size

    /** Return the minimum key reachable from [node] — i.e. the first key in the leftmost leaf. */
    private fun leftmostKey(node: Node<K, V>): K = when (node) {
        is LeafNode -> node.keyAt(0)
        is InternalNode -> leftmostKey(node.childAt(0))
    }

    /**
     * Bulk-load the tree from a pre-sorted Series2 of key/value pairs in O(n) time.
     * The tree must be empty; call on a freshly-constructed instance.
     *
     * The algorithm builds leaf nodes left-to-right filling each to [order] keys,
     * then builds internal levels bottom-up until a single root remains.
     */
    fun bulkLoad(sortedPairs: Series2<K, V>) {
        require(size() == 0) { "bulkLoad requires an empty tree" }
        if (sortedPairs.isEmpty()) return

        // --- pass 1: build leaf nodes ---
        val leaves = ArrayDeque<LeafNode>()
        var cur = LeafNode()
        for (i in 0 until sortedPairs.a) {
            val pair = sortedPairs[i]
            if (cur.keysCount == order) {
                leaves.add(cur)
                cur = LeafNode()
            }
            cur._keys[cur.keysCount] = pair.a
            cur._values[cur.keysCount] = pair.b
            cur.keysCount++
        }
        if (cur.keysCount > 0) leaves.add(cur)

        if (leaves.size == 1) {
            root = leaves[0]
            return
        }

        // --- pass 2+: build internal levels bottom-up until one root ---
        var currentLevel: List<Node<K, V>> = leaves
        while (currentLevel.size > 1) {
            val nextLevel = ArrayDeque<InternalNode>()
            var parent = InternalNode()
            parent._children[0] = currentLevel[0]
            parent.childrenCount = 1

            for (i in 1 until currentLevel.size) {
                val child = currentLevel[i]
                val pivotKey: K = leftmostKey(child)
                if (parent.childrenCount == order) {
                    nextLevel.add(parent)
                    parent = InternalNode()
                    parent._children[0] = child
                    parent.childrenCount = 1
                } else {
                    parent._keys[parent.keysCount] = pivotKey
                    parent.keysCount++
                    parent._children[parent.childrenCount] = child
                    parent.childrenCount++
                }
            }
            if (parent.childrenCount > 0) nextLevel.add(parent)

            // Ensure every non-root internal node has >= 2 children.
            // If the last node has only 1 child, borrow one from the penultimate.
            if (nextLevel.size >= 2 && nextLevel.last().childrenCount == 1) {
                val last = nextLevel.last()
                val prev = nextLevel[nextLevel.size - 2]
                val borrowedChild = prev.childAt(prev.childrenCount - 1)
                val newSeparator: K = leftmostKey(last.childAt(0))
                // Shift last's existing child to slot 1 and put borrowed child in slot 0.
                last._children[1] = last._children[0]
                last._children[0] = borrowedChild
                last._keys[0] = newSeparator
                last.keysCount = 1
                last.childrenCount = 2
                // Remove the borrowed child from prev.
                prev._children[prev.childrenCount - 1] = null
                prev._keys[prev.keysCount - 1] = null
                prev.childrenCount--
                prev.keysCount--
            }

            currentLevel = nextLevel
        }

        root = currentLevel[0]
    }

    @JvmName("bulkLoadFromList")
    fun bulkLoad(sortedPairs: List<Pair<K, V>>) {
        bulkLoad(sortedPairs.size j { i -> sortedPairs[i].first j sortedPairs[i].second })
    }

    fun validateFanoutBounds(): Boolean {
        data class Validation<K>(
            val ok: Boolean,
            val minKey: K?,
            val maxKey: K?,
            val leafDepth: Int,
        )

        fun invalid(depth: Int): Validation<K> = Validation(false, null, null, depth)

        fun walk(node: Node<K, V>, depth: Int, isRoot: Boolean): Validation<K> {
            if (node.isLeaf()) {
                val leaf = node as LeafNode
                if (leaf.keysCount < 0 || leaf.keysCount > order) return invalid(depth)
                if (!isRoot && leaf.keysCount == 0) return invalid(depth)
                for (i in 1 until leaf.keysCount) {
                    if (leaf.keyAt(i - 1).compareTo(leaf.keyAt(i)) >= 0) return invalid(depth)
                }
                val min = if (leaf.keysCount > 0) leaf.keyAt(0) else null
                val max = if (leaf.keysCount > 0) leaf.keyAt(leaf.keysCount - 1) else null
                return Validation(true, min, max, depth)
            }

            val internal = node as InternalNode
            if (internal.childrenCount == 0) return invalid(depth)
            if (internal.keysCount != internal.childrenCount - 1) return invalid(depth)
            if (internal.childrenCount > order) return invalid(depth)
            if (!isRoot && internal.childrenCount < 2) return invalid(depth)
            for (i in 1 until internal.keysCount) {
                if (internal.keyAt(i - 1).compareTo(internal.keyAt(i)) >= 0) return invalid(depth)
            }

            val children = ArrayDeque<Validation<K>>(internal.childrenCount)
            for (i in 0 until internal.childrenCount) {
                val childValidation = walk(internal.childAt(i), depth + 1, false)
                if (!childValidation.ok) return invalid(depth)
                children.add(childValidation)
            }

            val expectedLeafDepth = children[0].leafDepth
            for (child in children) {
                if (child.leafDepth != expectedLeafDepth) return invalid(depth)
            }

            for (i in 0 until internal.keysCount) {
                val separator = internal.keyAt(i)
                val leftMax = children[i].maxKey
                val rightMin = children[i + 1].minKey
                if (leftMax != null && leftMax.compareTo(separator) >= 0) return invalid(depth)
                if (rightMin != null && rightMin.compareTo(separator) < 0) return invalid(depth)
            }

            return Validation(
                ok = true,
                minKey = children.first().minKey,
                maxKey = children.last().maxKey,
                leafDepth = expectedLeafDepth,
            )
        }

        return walk(root, depth = 0, isRoot = true).ok
    }

    fun range(start: K, end: K): Sequence<Join<K, V>> = sequence {
        val stack = ArrayDeque<Join<Node<K, V>, Int>>()
        var cur = root

        while (!cur.isLeaf()) {
            val internal = cur as InternalNode
            var idx = internal.binarySearchKeys(start)
            var childIndex = if (idx >= 0) idx + 1 else -idx - 1
            if (childIndex < 0) childIndex = 0
            if (childIndex > internal.childrenCount - 1) childIndex = internal.childrenCount - 1
            stack.add(internal j childIndex)
            cur = internal.childAt(childIndex)
        }

        var leaf = cur as LeafNode
        var leafIdx = leaf.binarySearchKeys(start)
        if (leafIdx < 0) leafIdx = -leafIdx - 1

        while (true) {
            while (leafIdx < leaf.keysCount) {
                val k = leaf.keyAt(leafIdx)
                if (k >= end) return@sequence
                val v = leaf.valueAt(leafIdx) as V
                yield(k j v)
                leafIdx++
            }

            var nextNode: Node<K, V>? = null
            while (stack.isNotEmpty()) {
                val parentJoin = stack.removeLast()
                val parent = parentJoin.a as InternalNode
                val childIdx = parentJoin.b
                if (childIdx + 1 < parent.childrenCount) {
                    stack.add(parent j (childIdx + 1))
                    nextNode = parent.childAt(childIdx + 1)
                    break
                }
            }

            if (nextNode == null) break

            cur = nextNode
            while (!cur.isLeaf()) {
                val internal = cur as InternalNode
                stack.add(internal j 0)
                cur = internal.childAt(0)
            }
            leaf = cur as LeafNode
            leafIdx = 0
        }
    }

    private fun delete(node: Node<K, V>, key: K): Node<K, V>? {
        if (node.isLeaf()) {
            val leaf = node as LeafNode
            val idx = leaf.binarySearchKeys(key)
            if (idx >= 0) {
                val newLeaf = leaf.removeAt(idx)
                if (newLeaf.keysCount == 0) return null
                return newLeaf
            }
            return leaf
        } else {
            val internal = node as InternalNode
            val idx = internal.binarySearchKeys(key)
            var childIndex = if (idx >= 0) idx + 1 else -idx - 1
            if (childIndex < 0) childIndex = 0
            if (childIndex >= internal.childrenCount) childIndex = internal.childrenCount - 1

            val child = internal.childAt(childIndex)
            val newChild = delete(child, key)

            if (newChild == null) {
                val keyIdxToRemove = if (childIndex == 0) 0 else childIndex - 1
                return internal.removeKeyAndChildAt(keyIdxToRemove, childIndex)
            } else {
                var newInternal = internal.setChildAt(childIndex, newChild)
                val minKeys = order / 2

                if (newChild.isLeaf()) {
                    val leafChild = newChild as LeafNode
                    if (leafChild.keysCount < minKeys) {
                        val hasRight = childIndex < newInternal.childrenCount - 1
                        val hasLeft  = childIndex > 0

                        when {
                            // Borrow from right leaf sibling
                            hasRight && (newInternal.childAt(childIndex + 1) as LeafNode).keysCount > minKeys -> {
                                val right = newInternal.childAt(childIndex + 1) as LeafNode
                                val borrowedKey   = right.keyAt(0)
                                val borrowedValue = right.valueAt(0)
                                val newLeaf  = leafChild.insertAt(leafChild.keysCount, borrowedKey, borrowedValue as V)
                                val newRight = right.removeAt(0)
                                newInternal = newInternal.setChildAt(childIndex,     newLeaf)
                                newInternal = newInternal.setChildAt(childIndex + 1, newRight)
                                newInternal = newInternal.setKeyAt(childIndex, newRight.keyAt(0))
                            }
                            // Merge with right leaf sibling
                            hasRight && leafChild.keysCount + (newInternal.childAt(childIndex + 1) as LeafNode).keysCount <= order -> {
                                val right = newInternal.childAt(childIndex + 1) as LeafNode
                                var merged = leafChild
                                for (i in 0 until right.keysCount) {
                                    merged = merged.insertAt(merged.keysCount, right.keyAt(i), right.valueAt(i) as V)
                                }
                                newInternal = newInternal.setChildAt(childIndex, merged)
                                newInternal = newInternal.removeKeyAndChildAt(childIndex, childIndex + 1)
                            }
                            // Borrow from left leaf sibling
                            hasLeft && (newInternal.childAt(childIndex - 1) as LeafNode).keysCount > minKeys -> {
                                val left = newInternal.childAt(childIndex - 1) as LeafNode
                                val borrowedKey   = left.keyAt(left.keysCount - 1)
                                val borrowedValue = left.valueAt(left.keysCount - 1)
                                val newLeaf = leafChild.insertAt(0, borrowedKey, borrowedValue as V)
                                val newLeft = left.removeAt(left.keysCount - 1)
                                newInternal = newInternal.setChildAt(childIndex - 1, newLeft)
                                newInternal = newInternal.setChildAt(childIndex,     newLeaf)
                                newInternal = newInternal.setKeyAt(childIndex - 1, newLeaf.keyAt(0))
                            }
                            // Merge with left leaf sibling
                            hasLeft && (newInternal.childAt(childIndex - 1) as LeafNode).keysCount + leafChild.keysCount <= order -> {
                                val left = newInternal.childAt(childIndex - 1) as LeafNode
                                var merged = left
                                for (i in 0 until leafChild.keysCount) {
                                    merged = merged.insertAt(merged.keysCount, leafChild.keyAt(i), leafChild.valueAt(i) as V)
                                }
                                newInternal = newInternal.setChildAt(childIndex - 1, merged)
                                newInternal = newInternal.removeKeyAndChildAt(childIndex - 1, childIndex)
                            }
                        }
                    }
                } else {
                    val intChild = newChild as InternalNode
                    // Internal nodes are valid with keysCount >= 1 (childrenCount >= 2).
                    // Only rebalance when the node has dropped to 0 keys (1 child).
                    if (intChild.keysCount < 1) {
                        val hasRight = childIndex < newInternal.childrenCount - 1
                        val hasLeft  = childIndex > 0

                        when {
                            // Borrow from right internal sibling (sibling must be able to spare a child)
                            hasRight && (newInternal.childAt(childIndex + 1) as InternalNode).keysCount > 1 -> {
                                val right      = newInternal.childAt(childIndex + 1) as InternalNode
                                val sepIdx     = childIndex
                                val parentSep  = newInternal.keyAt(sepIdx)
                                val newLeaf    = intChild.insertKeyAt(intChild.keysCount, parentSep)
                                val withChild  = newLeaf.addChild(right.childAt(0))
                                val newRight   = right.removeKeyAndChildAt(0, 0)
                                newInternal = newInternal.setChildAt(childIndex, withChild)
                                newInternal = newInternal.setChildAt(childIndex + 1, newRight)
                                newInternal = newInternal.setKeyAt(sepIdx, right.keyAt(0))
                            }
                            // Merge with right internal sibling (use childrenCount arithmetic to avoid overflow)
                            hasRight && intChild.childrenCount + (newInternal.childAt(childIndex + 1) as InternalNode).childrenCount <= order -> {
                                val right = newInternal.childAt(childIndex + 1) as InternalNode
                                val pivot = newInternal.keyAt(childIndex)
                                var merged = intChild.insertKeyAt(intChild.keysCount, pivot)
                                for (i in 0 until right.keysCount) {
                                    merged = merged.insertKeyAt(merged.keysCount, right.keyAt(i))
                                }
                                for (i in 0 until right.childrenCount) {
                                    merged = merged.addChild(right.childAt(i))
                                }
                                newInternal = newInternal.setChildAt(childIndex, merged)
                                newInternal = newInternal.removeKeyAndChildAt(childIndex, childIndex + 1)
                            }
                            // Borrow from left internal sibling
                            hasLeft && (newInternal.childAt(childIndex - 1) as InternalNode).keysCount > 1 -> {
                                val left      = newInternal.childAt(childIndex - 1) as InternalNode
                                val sepIdx    = childIndex - 1
                                val parentSep = newInternal.keyAt(sepIdx)
                                val rightmost = left.childAt(left.childrenCount - 1)
                                var updated = intChild.insertKeyAt(0, parentSep)
                                updated = updated.insertChildAt(0, rightmost)
                                val newLeft = left.removeKeyAndChildAt(left.keysCount - 1, left.childrenCount - 1)
                                newInternal = newInternal.setChildAt(childIndex - 1, newLeft)
                                newInternal = newInternal.setChildAt(childIndex,     updated)
                                newInternal = newInternal.setKeyAt(sepIdx, left.keyAt(left.keysCount - 1))
                            }
                            // Merge with left internal sibling
                            hasLeft && (newInternal.childAt(childIndex - 1) as InternalNode).childrenCount + intChild.childrenCount <= order -> {
                                val left  = newInternal.childAt(childIndex - 1) as InternalNode
                                val pivot = newInternal.keyAt(childIndex - 1)
                                var merged = left.insertKeyAt(left.keysCount, pivot)
                                for (i in 0 until intChild.keysCount) {
                                    merged = merged.insertKeyAt(merged.keysCount, intChild.keyAt(i))
                                }
                                for (i in 0 until intChild.childrenCount) {
                                    merged = merged.addChild(intChild.childAt(i))
                                }
                                newInternal = newInternal.setChildAt(childIndex - 1, merged)
                                newInternal = newInternal.removeKeyAndChildAt(childIndex - 1, childIndex)
                            }
                        }
                    }
                }
                return newInternal
            }
        }
    }

    fun insert(node: Node<K, V>, key: K, value: V): Any {
        if (node.isLeaf()) {
            val leaf: BPlusTree<K, V>.LeafNode = node as LeafNode
            val idx = leaf.binarySearchKeys(key)
            if (idx >= 0) {
                return leaf.setValueAt(idx, value)
            }
            val insertAt = -idx - 1
            val newLeaf = leaf.insertAt(insertAt, key, value)
            if (newLeaf.keysCount > order) {
                val mid = newLeaf.keysCount / 2
                val (left, right) = newLeaf.splitAt(mid)
                val pivot = right.keyAt(0)
                return pivot j Twin(left, right)
            }
            return newLeaf
        } else {
            val internal: BPlusTree<K, V>.InternalNode = node as InternalNode
            val idx = internal.binarySearchKeys(key)
            var childIndex = if (idx >= 0) idx + 1 else -idx - 1
            if (childIndex < 0) childIndex = 0
            if (childIndex >= internal.childrenCount) childIndex = internal.childrenCount - 1
            val child = internal.childAt(childIndex)
            val result = insert(child, key, value)

            if (result is Join<*, *>) {
                val split = result as Join<K, Twin<Node<K, V>>>
                val pivot = split.a
                val (left, right) = split.b
                var newInternal = internal.setChildAt(childIndex, left)
                newInternal = newInternal.insertChildAt(childIndex + 1, right)
                newInternal = newInternal.insertKeyAt(childIndex, pivot)

                if (newInternal.childrenCount > order) {
                    val mid = newInternal.keysCount / 2
                    val (pivotKey, twins) = newInternal.splitAt(mid)
                    return pivotKey j twins
                }
                return newInternal
            } else {
                return internal.setChildAt(childIndex, result as Node<K, V>)
            }
        }
    }

    fun findLeaf(node: Node<K, V>, key: K): Node<K, V> {
        var cur = node
        while (!cur.isLeaf()) {
            val internal = cur as InternalNode
            val idx = internal.binarySearchKeys(key)
            var childIndex = if (idx >= 0) idx + 1 else -idx - 1
            if (childIndex < 0) childIndex = 0
            if (childIndex >= internal.childrenCount) childIndex = internal.childrenCount - 1
            cur = internal.childAt(childIndex)
        }
        return cur
    }
}
