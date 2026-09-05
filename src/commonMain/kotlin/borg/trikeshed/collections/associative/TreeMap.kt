package borg.trikeshed.collections.associative

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import kotlin.collections.MutableMap.*

/**
 * Portable comparator-ordered AVL map. Rotation cases are adapted from the local
 * collections/TreeSet.kt, with new immutable path copying and subtree counts.
 * This is not a port of Haskell containers (nor of the JDK TreeMap).
 *
 * Lookup, insertion, deletion, rank and select take O(log n). Updates allocate
 * O(log n) nodes; unmodified subtrees are shared. Full-map size is O(1), bounded
 * size O(log n). Iteration takes O(log n + m) time and O(log n) stack space over
 * m entries without intervening writes. Views share the mutable root holder.
 *
 * Comparator equality identifies keys; replacing a mapping preserves its first
 * key object. Null keys work when the comparator supports them; values may be
 * null. As with other sorted maps, Map equality requires an equals-consistent
 * comparator. Keys and comparator ordering must remain stable. Not thread-safe.
 * Iterators fail fast on structural changes, except their own remove; replacing
 * values is permitted. Entries read through to the current mapping and support
 * setValue. A read after a root change costs O(log n); other value reads O(1).
 * Removed entries retain their last observed value; setValue then throws.
 */
class TreeMap<K, V> private constructor(
    private val state: State<K, V>,
    private val lower: Bound<K>?,
    private val upper: Bound<K>?,
) : AbstractMutableMap<K, V>(), SortedMap<K, V> {
    constructor(comparator: Comparator<in K>) : this(State(comparator), null, null)

    private class Bound<K>(val key: K)

    private class Node<K, V>(
        val key: K,
        val value: V,
        val left: Node<K, V>? = null,
        val right: Node<K, V>? = null,
    ) {
        val height: Int = 1 + maxOf(left?.height ?: 0, right?.height ?: 0)
        val count: Int = 1 + (left?.count ?: 0) + (right?.count ?: 0)
    }

    private class State<K, V>(val comparator: Comparator<in K>) {
        var root: Node<K, V>? = null
        var version: Long = 0
    }

    override val comparator: Comparator<in K> get() = state.comparator
    private fun compare(a: K, b: K): Int = comparator.compare(a, b)
    private fun inRange(key: K): Boolean =
        (lower == null || compare(key, lower.key) >= 0) &&
            (upper == null || compare(key, upper.key) < 0)

    private fun find(key: K): Node<K, V>? {
        var node = state.root
        while (node != null) {
            val order = compare(key, node.key)
            node = when {
                order < 0 -> node.left
                order > 0 -> node.right
                else -> return node
            }
        }
        return null
    }

    private fun balanceFactor(node: Node<K, V>): Int =
        (node.left?.height ?: 0) - (node.right?.height ?: 0)

    private fun rotateRight(node: Node<K, V>): Node<K, V> {
        val child = node.left!!
        return Node(child.key, child.value, child.left,
            Node(node.key, node.value, child.right, node.right))
    }

    private fun rotateLeft(node: Node<K, V>): Node<K, V> {
        val child = node.right!!
        return Node(child.key, child.value,
            Node(node.key, node.value, node.left, child.left), child.right)
    }

    private fun balance(node: Node<K, V>): Node<K, V> = when {
        balanceFactor(node) > 1 -> rotateRight(
            if (balanceFactor(node.left!!) >= 0) node
            else Node(node.key, node.value, rotateLeft(node.left), node.right))
        balanceFactor(node) < -1 -> rotateLeft(
            if (balanceFactor(node.right!!) <= 0) node
            else Node(node.key, node.value, node.left, rotateRight(node.right)))
        else -> node
    }

    private fun insert(node: Node<K, V>?, key: K, value: V): Node<K, V> {
        if (node == null) return Node(key, value)
        val order = compare(key, node.key)
        return balance(when {
            order < 0 -> Node(node.key, node.value, insert(node.left, key, value), node.right)
            order > 0 -> Node(node.key, node.value, node.left, insert(node.right, key, value))
            else -> Node(node.key, value, node.left, node.right)
        })
    }

    private fun minimum(node: Node<K, V>): Node<K, V> {
        var current = node
        while (current.left != null) current = current.left
        return current
    }

    private fun delete(node: Node<K, V>?, key: K): Node<K, V>? {
        node ?: return null
        val order = compare(key, node.key)
        return balance(when {
            order < 0 -> Node(node.key, node.value, delete(node.left, key), node.right)
            order > 0 -> Node(node.key, node.value, node.left, delete(node.right, key))
            else -> {
                when {
                    node.left == null -> return node.right
                    node.right == null -> return node.left
                    else -> {
                        val next = minimum(node.right)
                        Node(next.key, next.value, node.left, delete(node.right, next.key))
                    }
                }
            }
        })
    }

    private companion object {
        fun <K, V> countBefore(root: Node<K, V>?, key: K, comparator: Comparator<in K>): Int {
            var node: Node<K, V>? = root
            var count = 0
            while (node != null)
                when {
                    comparator.compare(key, node.key) <= 0 -> node = node.left
                    else -> {
                        count += 1 + (node.left?.count ?: 0)
                        node = node.right
                    }
                }
            return count
        }

        fun <K, V> nodeAt(root: Node<K, V>?, index: Int): Node<K, V> {
            when {
                index < 0 || index >= (root?.count ?: 0) -> throw IndexOutOfBoundsException("index: $index")
            }
            var node = root!!
            var offset = index
            while (true) {
                val leftCount = node.left?.count ?: 0
                when {
                    offset < leftCount -> node = node.left!!
                    offset == leftCount -> return node
                    else -> {
                        offset -= leftCount + 1
                        node = node.right!!
                    }
                }
            }
        }
    }

    private fun start(root: Node<K, V>?): Int = lower?.let { countBefore(root, it.key, comparator) } ?: 0
    private fun end(root: Node<K, V>?): Int =
        upper?.let { countBefore(root, it.key, comparator) } ?: (root?.count ?: 0)

    override val size: Int get() = end(state.root) - start(state.root)
    override fun containsKey(key: K): Boolean = inRange(key) && find(key) != null
    override fun get(key: K): V? = if (inRange(key)) find(key)?.value else null

    override fun put(key: K, value: V): V? {
        require(inRange(key)) { "Key outside view bounds" }
        // Validate even the first key, when there is no tree node to compare to.
        compare(key, key)
        val old: Node<K, V>? = find(key)
        state.root = insert(state.root, key, value)
        if (old == null) state.version++
        return old?.value
    }

    override fun remove(key: K): V? {
        if (!inRange(key)) return null
        val old: Node<K, V> = find(key) ?: return null
        state.root = delete(state.root, key)
        state.version++
        return old.value
    }

    /** Full clear is O(1); clearing a bounded view removes only its m keys in O(m log n). */
    override fun clear() {
        when {
            lower != null || upper != null -> {
                val iterator = entries.iterator()
                while (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            state.root != null -> {
                state.root = null
                state.version++
            }
        }
    }

    // Inclusive starts cannot equal an excluded upper bound. Exclusive ends can.
    private fun validateEndpoint(key: K, inclusive: Boolean) {
        compare(key, key)
        require(lower == null || compare(key, lower.key) >= 0) { "Endpoint below view bounds" }
        require(upper == null || compare(key, upper.key).let { it < 0 || (!inclusive && it == 0) }) { "Endpoint above view bounds" }
    }

    override fun subMap(fromKey: K, toKey: K): TreeMap<K, V> {
        require(compare(fromKey, toKey) <= 0) { "Reversed bounds" }
        validateEndpoint(fromKey, true)
        validateEndpoint(toKey, false)
        return TreeMap(state, Bound(fromKey), Bound(toKey))
    }

    override fun headMap(toKey: K): TreeMap<K, V> {
        validateEndpoint(toKey, false)
        return TreeMap(state, lower, Bound(toKey))
    }

    override fun tailMap(fromKey: K): TreeMap<K, V> {
        validateEndpoint(fromKey, true)
        return TreeMap(state, Bound(fromKey), upper)
    }

    override fun firstKey(): K {
        val root = state.root
        val first = start(root)
        when (first) {
            end(root) -> throw NoSuchElementException("Empty map")
        }
        return nodeAt(root, first).key
    }

    override fun lastKey(): K {
        val root = state.root
        val last = end(root)
        if (start(root) == last) throw NoSuchElementException("Empty map")
        return nodeAt(root, last - 1).key
    }

    /** Number of keys in this view strictly before [key], including absent keys. */
    fun rank(key: K): Int {
        val root: Node<K, V>? = state.root
        val first: Int = this.start(root)
        return countBefore(root, key, comparator).coerceIn(first, end(root)) - first
    }

    /** Mapping at a zero-based comparator-order index within this view. */
    fun select(index: Int): Join<K, V> {
        val root = state.root
        val first = start(root)
        if (index < 0 || index >= end(root) - first) throw IndexOutOfBoundsException("index: $index")
        val node: Node<K, V> = nodeAt(root, first + index)
        return node.key j node.value
    }

    /**
     * O(1) stable root capture, including for bounded views. Bounds are evaluated
     * lazily against that root in O(log n) on first access; indexed reads are
     * O(log n). Keys/values themselves are shared references, not deep copies.
     * Holding a snapshot retains its frozen tree, comparator and bounds, but
     * neither the live map/root holder nor roots produced by subsequent writes.
     */
    fun snapshot(): Series2<K, V> = Snapshot(state.root, comparator, lower, upper)

    private class Snapshot<K, V>(
        private val root: Node<K, V>?,
        private val comparator: Comparator<in K>,
        private val lower: Bound<K>?,
        private val upper: Bound<K>?,
    ) : Series2<K, V> {
        private val first by lazy { lower?.let { countBefore(root, it.key, comparator) } ?: 0 }
        override val a: Int by lazy {
            (upper?.let { countBefore(root, it.key, comparator) } ?: (root?.count ?: 0)) - first
        }
        override val b: (Int) -> Join<K, V> = { index ->
            if (index < 0 || index >= a) throw IndexOutOfBoundsException("index: $index")
            val node = nodeAt(root, first + index)
            node.key j node.value
        }
    }

    private inner class LiveEntry(node: Node<K, V>, root: Node<K, V>?) : MutableEntry<K, V> {
        override val key: K = node.key
        private var cachedValue: V = node.value
        private var cachedRoot = root
        override val value: V
            get() {
                when {
                    cachedRoot !== state.root -> {
                        val current = find(key)
                        if (current != null) cachedValue = current.value
                        cachedRoot = state.root
                    }
                }
                return cachedValue
            }

        override fun setValue(newValue: V): V {
            val current: Node<K, V> = find(key) ?: throw IllegalStateException("Entry no longer in map")
            put(key, newValue)
            cachedValue = newValue
            cachedRoot = state.root
            return current.value
        }

        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && key == other.key && value == other.value
        override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
        override fun toString(): String = "$key=$value"
    }

    private inner class EntryIterator : MutableIterator<MutableEntry<K, V>> {
        private val root: Node<K, V>? = state.root
        private val stack: MutableList<Node<K, V>> = mutableListOf<Node<K, V>>()
        private var expectedVersion: Long = state.version
        private var last: Node<K, V>? = null

        init { pushLeft(root) }

        private fun pushLeft(root: Node<K, V>?) {
            var node: Node<K, V>? = root
            while (node != null) {
                if (lower != null && compare(node.key, lower.key) < 0) node = node.right
                else {
                    stack.add(node)
                    node = node.left
                }
            }
        }

        private fun checkVersion() {
            if (expectedVersion != state.version) throw ConcurrentModificationException()
        }

        override fun hasNext(): Boolean {
            checkVersion()
            return stack.isNotEmpty() && (upper == null || compare(stack.last().key, upper.key) < 0)
        }

        override fun next(): MutableEntry<K, V> {
            if (!hasNext()) throw NoSuchElementException()
            val node: Node<K, V> = stack.removeAt(stack.lastIndex)
            pushLeft(node.right)
            last = node
            return LiveEntry(node, root)
        }

        override fun remove() {
            checkVersion()
            val node: Node<K, V> = last ?: throw IllegalStateException("Call next before remove")
            remove(node.key)
            expectedVersion = state.version
            last = null
        }
    }

    private fun entryMatches(a: Map.Entry<K, V>, b: Map.Entry<K, V>): Boolean =
        compare(a.key, b.key) == 0 && a.value == b.value

    private fun <T> removeMatching(iterator: MutableIterator<T>, predicate: (T) -> Boolean): Boolean {
        var changed = false
        while (iterator.hasNext()) {
            if (predicate(iterator.next())) {
                iterator.remove()
                changed = true
            }
        }
        return changed
    }

    override val entries: MutableSet<MutableEntry<K, V>> =
        object : AbstractMutableSet<MutableEntry<K, V>>() {
            override val size: Int get() = this@TreeMap.size
            override fun iterator(): MutableIterator<MutableEntry<K, V>> = EntryIterator()
            override fun add(element: MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()
            override fun contains(element: MutableEntry<K, V>): Boolean {
                if (!inRange(element.key)) return false
                val node: Node<K, V> = find(element.key) ?: return false
                return node.value == element.value
            }
            override fun remove(element: MutableEntry<K, V>): Boolean {
                if (!contains(element)) return false
                this@TreeMap.remove(element.key)
                return true
            }
            override fun clear() = this@TreeMap.clear()
            override fun removeAll(elements: Collection<MutableEntry<K, V>>) =
                removeMatching(iterator()) { entry -> elements.any { entryMatches(entry, it) } }
            override fun retainAll(elements: Collection<MutableEntry<K, V>>): Boolean =
                removeMatching(iterator()) { entry -> elements.none { entryMatches(entry, it) } }
        }

    private fun <T> projectIterator(project: (MutableEntry<K, V>) -> T): MutableIterator<T> {
        val source: MutableIterator<MutableEntry<K, V>> = entries.iterator()
        return object : MutableIterator<T> {
            override fun hasNext(): Boolean = source.hasNext()
            override fun next(): T = project(source.next())
            override fun remove() = source.remove()
        }
    }

    override val keys: MutableSet<K> = object : AbstractMutableSet<K>() {
        override val size: Int get() = this@TreeMap.size
        override fun iterator(): MutableIterator<K> = projectIterator { it.key }
        override fun add(element: K): Boolean = throw UnsupportedOperationException()
        override fun contains(element: K): Boolean = containsKey(element)
        override fun remove(element: K): Boolean {
            if (!containsKey(element)) return false
            this@TreeMap.remove(element)
            return true
        }
        override fun clear() = this@TreeMap.clear()
        override fun removeAll(elements: Collection<K>): Boolean =
            removeMatching(iterator()) { key -> elements.any { compare(key, it) == 0 } }
        override fun retainAll(elements: Collection<K>): Boolean =
            removeMatching(iterator()) { key -> elements.none { compare(key, it) == 0 } }
    }

    override val values: MutableCollection<V> = object : AbstractMutableCollection<V>() {
        override val size: Int get() = this@TreeMap.size
        override fun iterator(): MutableIterator<V> = projectIterator { it.value }
        override fun add(element: V): Boolean = throw UnsupportedOperationException()
        override fun clear() = this@TreeMap.clear()
    }

    /** Test diagnostic: verifies strict ordering, AVL heights and subtree counts. */
    internal fun checkInvariants(): Int {
        fun checkNode(node: Node<K, V>?, low: Bound<K>?, high: Bound<K>?): Join<Int, Int> {
            if (node == null) return 0 j 0
            check(low == null || compare(low.key, node.key) < 0)
            check(high == null || compare(node.key, high.key) < 0)
            val left = checkNode(node.left, low, Bound(node.key))
            val right = checkNode(node.right, Bound(node.key), high)
            check(left.a - right.a in -1..1)
            check(node.height == 1 + maxOf(left.a, right.a))
            check(node.count == 1 + left.b + right.b)
            return node.height j node.count
        }
        return checkNode(state.root, null, null).a
    }
}

/** Copy into a portable map using natural key order. */
fun <K : Comparable<K>, V> Map<K, V>.toCommonSortedMap(): TreeMap<K, V> =
    toCommonSortedMap(Comparator { a, b -> a.compareTo(b) })

/** Copy into a portable map; comparator-equivalent keys collapse in input iteration order. */
fun <K, V> Map<K, V>.toCommonSortedMap(comparator: Comparator<in K>): TreeMap<K, V> =
    TreeMap<K, V>(comparator).also { it.putAll(this) }
