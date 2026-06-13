@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.tinybtrfs

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j
import borg.trikeshed.cursor.MutableSeries
import borg.trikeshed.cursor.JournalSeries
import borg.trikeshed.cursor.RingSeries

/** COW extension functions for MutableSeries */
fun <T> MutableSeries<T>.cowAdd(item: T): MutableSeries<T> = when (this) {
    is ArrayBackedSeries<T> -> this.cowAdd(item)
    else -> throw UnsupportedOperationException("cowAdd not supported for ${this.javaClass}")
}

fun <T> MutableSeries<T>.cowSet(index: Int, item: T): MutableSeries<T> = when (this) {
    is ArrayBackedSeries<T> -> this.cowSet(index, item)
    else -> throw UnsupportedOperationException("cowSet not supported for ${this.javaClass}")
}

/**
 * Factory for creating MutableSeries backings.
 * Allows pluggable storage: Array, RingSeries, JournalSeries, etc.
 */
interface MutableSeriesFactory {
    /** Create empty series with capacity hint */
    fun <T> create(capacity: Int): MutableSeries<T>
    
    /** Create series from existing elements */
    fun <T> of(vararg elements: T): MutableSeries<T>
}

/** Default array-backed factory (current behavior) */
object ArraySeriesFactory : MutableSeriesFactory {
    override fun <T> create(capacity: Int): MutableSeries<T> = ArrayBackedSeries(capacity)
    override fun <T> of(vararg elements: T): MutableSeries<T> = ArrayBackedSeries(*elements)
}

/** Ring buffer factory (O(1) insert, natural for LSM) */
object RingSeriesFactory : MutableSeriesFactory {
    override fun <T> create(capacity: Int): MutableSeries<T> = RingSeries<T>(capacity)
    override fun <T> of(vararg elements: T): MutableSeries<T> = RingSeries(elements).also { it.capacity = elements.size }
}

/** Journal/WAL factory (crash recovery, snapshots) */
object JournalSeriesFactory : MutableSeriesFactory {
    override fun <T> create(capacity: Int): MutableSeries<T> = JournalSeries()
    override fun <T> of(vararg elements: T): MutableSeries<T> = JournalSeries().also { elements.forEach { it.add(it) } }
}

/**
 * Array-backed MutableSeries - current behavior, COW via copy
 */
class ArrayBackedSeries<T>(private var data: Array<Any?> = arrayOfNulls(32)) : MutableSeries<T> {
    private var _size = 0
    
    override val a: Int get() = _size
    @Suppress("UNCHECKED_CAST")
    override val b: (Int) -> T = { i -> data[i] as T }
    
    override fun set(index: Int, item: T) {
        require(index in 0 until _size)
        data[index] = item
    }
    
    override fun add(item: T) {
        if (_size == data.size) data = data.copyOf(_size * 2)
        data[_size++] = item
    }
    
    override fun removeAt(index: Int): T {
        require(index in 0 until _size)
        @Suppress("UNCHECKED_CAST")
        val removed = data[index] as T
        for (i in index until _size - 1) data[i] = data[i + 1]
        data[--_size] = null
        return removed
    }
    
    /** COW: return new series with item added */
    fun cowAdd(item: T): ArrayBackedSeries<T> = ArrayBackedSeries(data.copyOf()).also { it._size = _size; it.add(item) }
    
    /** COW: return new series with item set */
    fun cowSet(index: Int, item: T): ArrayBackedSeries<T> = ArrayBackedSeries(data.copyOf()).also { it._size = _size; it.set(index, item) }
    
    /** COW: return new series with item removed */
    fun cowRemoveAt(index: Int): ArrayBackedSeries<T> = ArrayBackedSeries(data.copyOf()).also { it._size = _size; it.removeAt(index) }
}

/**
 * Snapshot handle - captures root at point in time for COW isolation.
 * Old snapshots remain valid after mutations (btrfs-style).
 */
data class TreeSnapshot<K : Comparable<K>, V>(
    val root: BPlusTree<K, V>.Node<K, V>,
    val version: Long,
    val size: Int
) {
    /** Query this snapshot - reads are isolated from later mutations */
    fun query(key: K): V? {
        val leaf = findLeaf(root, key) as LeafNode
        val idx = leaf.binarySearchKeys(key)
        return if (idx >= 0) leaf.valueAt(idx) else null
    }
    
    /** Range query on this snapshot */
    fun rangeQuery(start: K, end: K): List<V> {
        val result = mutableListOf<V>()
        var current: LeafNode? = findLeaf(root, start) as LeafNode?
        while (current != null) {
            for (i in 0 until current.keysCount) {
                val key = current.keyAt(i)
                if (key.compareTo(start) >= 0 && key.compareTo(end) < 0) {
                    result.add(current.valueAt(i)!!)
                } else if (key.compareTo(end) >= 0) return result
            }
            current = current.next
        }
        return result
    }
    
    private fun findLeaf(node: BPlusTree<K, V>.Node<K, V>, key: K): BPlusTree<K, V>.Node<K, V> {
        var cur = node
        while (!cur.isLeaf()) {
            val internal = cur as BPlusTree<K, V>.InternalNode
            val idx = internal.binarySearchKeys(key)
            var childIndex = if (idx >= 0) idx + 1 else -idx - 1
            if (childIndex < 0) childIndex = 0
            if (childIndex >= internal.childrenCount) childIndex = internal.childrenCount - 1
            cur = internal.childAt(childIndex)
        }
        return cur
    }
}

/**
 * Compression codec interface for node data.
 * Placed at leaf level for value compression, internal for key compression.
 */
interface CompressionCodec {
    fun compress(input: ByteArray): ByteArray
    fun decompress(input: ByteArray): ByteArray
    val name: String
}

/** No compression (default) */
object NoCompression : CompressionCodec {
    override fun compress(input: ByteArray) = input
    override fun decompress(input: ByteArray) = input
    override val name = "none"
}

/** LZ4 - fast, good ratio */
object Lz4Compression : CompressionCodec {
    override fun compress(input: ByteArray): ByteArray = input // TODO: LZ4
    override fun decompress(input: ByteArray): ByteArray = input
    override val name = "lz4"
}

/** ZSTD - better ratio, slower */
object ZstdCompression : CompressionCodec {
    override fun compress(input: ByteArray): ByteArray = input // TODO: ZSTD
    override fun decompress(input: ByteArray): ByteArray = input
    override val name = "zstd"
}

class BPlusTree<K : Comparable<K>, V>(
    val order: Int = 32,
    val factory: MutableSeriesFactory = ArraySeriesFactory,
    val leafCompression: CompressionCodec = NoCompression,
    val internalCompression: CompressionCodec = NoCompression
) {
    init { require(order >= 3) { "order must be >= 3" } }
    
    private var versionCounter: Long = 0
    var root: Node<K, V> = LeafNode(factory, order)
    var _size = 0
    
    fun snapshot(): TreeSnapshot<K, V> {
        versionCounter++
        return TreeSnapshot(root, versionCounter, _size)
    }

    sealed class Node<K, V> {
        /** Dense-array-backed Series view of the keys in this node. */
        abstract val keySeries: Series<K>
        abstract fun isLeaf(): Boolean
    }

    /** Result of a COW insert operation. */
    sealed class InsertResult<K, V> {
        data class NoSplit<K, V>(val newNode: Node<K, V>) : InsertResult<K, V>()
        data class Split<K, V>(
            val pivot: K,
            val left: Node<K, V>,
            val right: Node<K, V>
        ) : InsertResult<K, V>()
    }

    inner class LeafNode(
        private val _keys: MutableSeries<K>,
        private val _values: MutableSeries<V?>,
        val keysCount: Int = 0,
        val next: LeafNode? = null
    ) : Node<K, V>() {
    
    constructor(factory: MutableSeriesFactory, order: Int) : this(factory.create(order + 1), factory.create(order + 1), 0, null)

        override val keySeries: Series<K>
            get() = keysCount j { i: Int -> _keys[i] }

        val valueSeries: Series<V?>
            get() = keysCount j { i: Int -> _values[i] }

        override fun isLeaf() = true

        fun keyAt(i: Int): K = _keys[i]

        fun valueAt(i: Int): V? {
            if (i < 0 || i >= keysCount) return null
            return _values[i]
        }

        fun binarySearchKeys(key: K): Int {
            var low = 0
            var high = keysCount - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                @Suppress("UNCHECKED_CAST")
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

        /** COW: returns new LeafNode with key/value inserted (or updated). */
        fun insertAt(index: Int, key: K, value: V): LeafNode {
            // Check if key already exists at index
            if (index < keysCount && keyAt(index) == key) {
                val newValues = _values.cowSet(index, value)
                return LeafNode(_keys, newValues, keysCount, next)
            }
            val newKeys: MutableSeries<K> = _keys.cowAdd(key)
            val newValues: MutableSeries<V?> = _values.cowAdd(value)
            // Shift elements after index
            for (i in keysCount downTo index + 1) {
                newKeys.set(i, newKeys.b(i - 1))
                newValues.set(i, newValues.b(i - 1))
            }
            newKeys.set(index, key)
            newValues.set(index, value)
            return LeafNode(newKeys, newValues, keysCount + 1, next)
        }

        /** COW: splits this leaf at mid, returning (newLeft, newRight) without mutating this. */
        fun splitAt(mid: Int): Twin<LeafNode> {
            val leftKeys = this@BPlusTree.factory.create<K>(order + 1)
            val leftValues = this@BPlusTree.factory.create<V?>(order + 1)
            val rightKeys = this@BPlusTree.factory.create<K>(order + 1)
            val rightValues = this@BPlusTree.factory.create<V?>(order + 1)

            // Left: [0, mid)
            for (i in 0 until mid) {
                leftKeys.add(_keys.b(i))
                leftValues.add(_values.b(i))
            }
            // Right: [mid, keysCount)
            val rightLen = keysCount - mid
            for (i in 0 until rightLen) {
                rightKeys.add(_keys.b(mid + i))
                rightValues.add(_values.b(mid + i))
            }

            val newLeft = LeafNode(leftKeys, leftValues, mid, this.next)
            val newRight = LeafNode(rightKeys, rightValues, rightLen, this.next)
            return newLeft j newRight
        }

        /** COW: returns new LeafNode with updated next pointer. */
        fun withNext(newNext: LeafNode?): LeafNode {
            return LeafNode(_keys, _values, keysCount, newNext)
        }
    }

    inner class InternalNode(
        private val _keys: MutableSeries<K>,
        private val _children: MutableSeries<Node<K, V>>,
        val keysCount: Int = 0,
        val childrenCount: Int = 0
    ) : Node<K, V>() {
    
    constructor(factory: MutableSeriesFactory, order: Int) : this(factory.create(order + 1), factory.create(order + 2), 0, 0)

        override val keySeries: Series<K>
            get() = keysCount j { i -> _keys.b(i) }

        val childSeries: Series<Node<K, V>>
            get() = childrenCount j { i -> _children.b(i) }

        override fun isLeaf() = false

        fun keyAt(i: Int): K = _keys.b(i)

        fun childAt(i: Int): Node<K, V> = _children.b(i)

        fun binarySearchKeys(key: K): Int {
            var low = 0
            var high = keysCount - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val midVal: K = _keys.b(mid)
                val cmp = midVal.compareTo(key)
                when {
                    cmp < 0 -> low = mid + 1
                    cmp > 0 -> high = mid - 1
                    else -> return mid
                }
            }
            return -(low + 1)
        }

        /** COW: returns new InternalNode with key inserted at index. */
        fun insertKeyAt(index: Int, key: K): InternalNode {
            val newKeys = _keys.cowAdd(key)
            for (i in keysCount downTo index + 1) {
                newKeys.set(i, newKeys.b(i - 1))
            }
            newKeys.set(index, key)
            return InternalNode(newKeys, _children, keysCount + 1, childrenCount)
        }

        /** COW: returns new InternalNode with child set at index. */
        fun setChildAt(index: Int, child: Node<K, V>): InternalNode {
            val newChildren = _children.cowSet(index, child)
            return InternalNode(_keys, newChildren, keysCount, childrenCount)
        }

        /** COW: returns new InternalNode with child inserted at index. */
        fun insertChildAt(index: Int, child: Node<K, V>): InternalNode {
            val newChildren = _children.cowAdd(child)
            for (i in childrenCount downTo index + 1) {
                newChildren.set(i, newChildren.b(i - 1))
            }
            newChildren.set(index, child)
            return InternalNode(_keys, newChildren, keysCount, childrenCount + 1)
        }

        /** COW: returns new InternalNode with child appended. */
        fun addChild(child: Node<K, V>): InternalNode {
            val newChildren = _children.cowAdd(child)
            return InternalNode(_keys, newChildren, keysCount, childrenCount + 1)
        }

        fun splitAt(mid: Int): Join<K, Twin<InternalNode>> {
            val pivotKey: K = _keys.b(mid)
            
            val leftKeys = this@BPlusTree.factory.create<K>(order + 1)
            val leftChildren = this@BPlusTree.factory.create<Node<K, V>>(order + 2)
            val rightKeys = this@BPlusTree.factory.create<K>(order + 1)
            val rightChildren = this@BPlusTree.factory.create<Node<K, V>>(order + 2)

            // Left keys: [0, mid)
            for (i in 0 until mid) leftKeys.add(_keys.b(i))
            // Left children: [0, mid]
            for (i in 0 until mid + 1) leftChildren.add(_children.b(i))
            // Right keys: [mid+1, keysCount)
            val rightKeyLen = keysCount - mid - 1
            for (i in 0 until rightKeyLen) rightKeys.add(_keys.b(mid + 1 + i))
            // Right children: [mid+1, childrenCount)
            val rightChildLen = childrenCount - mid - 1
            for (i in 0 until rightChildLen) rightChildren.add(_children.b(mid + 1 + i))

            val newLeft = InternalNode(leftKeys, leftChildren, mid, mid + 1)
            val newRight = InternalNode(rightKeys, rightChildren, rightKeyLen, rightChildLen)
            return pivotKey j (newLeft j newRight)
        }
    }

    /** Lookup value by key. */
    fun get(key: K): V? {
        val leaf: BPlusTree<K, V>.LeafNode = findLeaf(root, key) as LeafNode
        val idx = leaf.binarySearchKeys(key)
        return if (idx >= 0) leaf.valueAt(idx) else null
    }

    fun size(): Int = _size

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

    /**
     * Returns all values for keys in [start, end) range.
     * Uses leaf node next pointers for efficient range traversal.
     */
    fun rangeQuery(start: K, end: K): List<V> {
        val result = mutableListOf<V>()
        // Find the leaf containing start key
        var current: LeafNode? = findLeaf(root, start) as LeafNode?
        
        while (current != null) {
            for (i in 0 until current.keysCount) {
                val key = current.keyAt(i)
                // Check if key is in [start, end)
                if (key.compareTo(start) >= 0 && key.compareTo(end) < 0) {
                    result.add(current.valueAt(i)!!)
                } else if (key.compareTo(end) >= 0) {
                    // Past the end, stop
                    return result
                }
            }
            // Move to next leaf
            current = current.next
        }
        return result
    }

    /**
     * COW internal insert. Returns InsertResult.
     * Never mutates the input node - always returns new nodes.
     */
    fun insert(node: Node<K, V>, key: K, value: V): InsertResult<K, V> {
        if (node.isLeaf()) {
            val leaf: BPlusTree<K, V>.LeafNode = node as LeafNode
            val idx = leaf.binarySearchKeys(key)
            if (idx >= 0) {
                // Update existing key - return new leaf with updated value
                return InsertResult.NoSplit(leaf.insertAt(idx, key, value))
            }
            val insertAt = -idx - 1
            val newLeaf = leaf.insertAt(insertAt, key, value)
            if (newLeaf.keysCount > order) {
                val mid = newLeaf.keysCount / 2
                val (newLeft, newRight) = newLeaf.splitAt(mid)
                val pivot = newRight.keyAt(0)
                // Link leaves for range queries
                val linkedLeft = newLeft.withNext(newRight)
                return InsertResult.Split(pivot, linkedLeft, newRight)
            }
            return InsertResult.NoSplit(newLeaf)
        } else {
            val internal: BPlusTree<K, V>.InternalNode = node as InternalNode
            val idx = internal.binarySearchKeys(key)
            var childIndex = if (idx >= 0) idx + 1 else -idx - 1
            if (childIndex < 0) childIndex = 0
            if (childIndex >= internal.childrenCount) childIndex = internal.childrenCount - 1
            val child = internal.childAt(childIndex)
            val childResult = insert(child, key, value)
            return when (childResult) {
                is InsertResult.NoSplit -> {
                    val newInternal = internal.setChildAt(childIndex, childResult.newNode)
                    InsertResult.NoSplit(newInternal)
                }
                is InsertResult.Split -> {
                    val (pivot, left, right) = childResult
                    val withLeft = internal.setChildAt(childIndex, left)
                    val withRight = withLeft.insertChildAt(childIndex + 1, right)
                    val withPivot = withRight.insertKeyAt(childIndex, pivot)
                    if (withPivot.keysCount > order) {
                        val mid = withPivot.keysCount / 2
                        val splitResult = withPivot.splitAt(mid)
                        val pivotKey = splitResult.a
                        val (newLeft, newRight) = splitResult.b
                        InsertResult.Split(pivotKey, newLeft, newRight)
                    } else {
                        InsertResult.NoSplit(withPivot)
                    }
                }
            }
        }
    }

    /**
     * Public put with COW - returns new root on split.
     */
    fun put(key: K, value: V) {
        val existed: Boolean = get(key) != null
        val result = insert(root, key, value)
        when (result) {
            is InsertResult.NoSplit -> {
                root = result.newNode
            }
            is InsertResult.Split -> {
                val (pivot, left, right) = result
                val newRoot = InternalNode(factory, order)
                    .insertKeyAt(0, pivot)
                    .addChild(left)
                    .addChild(right)
                root = newRoot
            }
        }
        if (!existed) _size++
    }
}
