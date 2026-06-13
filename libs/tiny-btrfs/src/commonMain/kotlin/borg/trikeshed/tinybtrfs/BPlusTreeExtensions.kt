package borg.trikeshed.tinybtrfs

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j

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
    fun get(key: K): V? {
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

/** B+Tree with pluggable backing, snapshots, compression */
class BPlusTree<K : Comparable<K>, V>(
    val order: Int = 32,
    val factory: MutableSeriesFactory = ArraySeriesFactory,
    val leafCompression: CompressionCodec = NoCompression,
    val internalCompression: CompressionCodec = NoCompression
) {
    init { require(order >= 3) { "order must be >= 3" } }
    
    sealed class Node<K, V> {
        abstract val keySeries: Series<K>
        abstract fun isLeaf(): Boolean
    }
    
    sealed class InsertResult<K, V> {
        data class NoSplit<K, V>(val newNode: Node<K, V>) : InsertResult<K, V>()
        data class Split<K, V>(
            val pivot: K,
            val left: Node<K, V>,
            val right: Node<K, V>
        ) : InsertResult<K, V>()
    }
    
    private var versionCounter: Long = 0
    
    fun snapshot(): TreeSnapshot<K, V> {
        versionCounter++
        return TreeSnapshot(root, versionCounter, _size)
    }
    
    // ... rest of implementation uses factory for node creation
    // LeafNode/InternalNode would use factory.create(order + 1) instead of arrayOfNulls
}