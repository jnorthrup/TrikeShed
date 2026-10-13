package borg.trikeshed.collections.associative

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import borg.trikeshed.collections.SeriesBuffer
import borg.trikeshed.collections.MinHeap

/**
 * Base class for open-addressing hash maps with triangular probing.
 * Subclasses provide the key representation and optional order tracking.
 *
 * Type parameters:
 *   K — user key type
 *   V — value type
 *   IK — internal key representation (K for both provided maps; order is primitive slot metadata)
 */
abstract class OpenAddressingMap<K : Any, V, IK : Any>(
    initialCapacity: Int = 16
) {

    protected var capacity: Int = nextPowerOfTwo(initialCapacity.coerceAtLeast(4))
    protected var keys: Array<Any?> = newAbsentArray(capacity)
    protected var values: Array<Any?> = newAbsentArray(capacity)
    protected var size: Int = 0
    protected var tombstones: Int = 0

    // ─── sentinel markers (target-stable across JVM/JS/Wasm) ───
    companion object {
        protected val DELETED = Any()
        protected val ABSENT  = Any()

        protected fun mix(hash: Int): Int {
            var h = hash
            h = h xor (h ushr 16)
            h *= 0x85ebca6b.toInt()
            h = h xor (h ushr 13)
            h *= 0xc2b2ae35.toInt()
            h = h xor (h ushr 16)
            return h
        }

        protected fun nextPowerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }

        protected fun newAbsentArray(n: Int): Array<Any?> = Array(n) { ABSENT }

        protected fun isAbsent(slotValue: Any?): Boolean = slotValue === ABSENT
        protected fun isDeleted(slotValue: Any?): Boolean = slotValue === DELETED

        protected fun triangularProbe(hash: Int, i: Int, cap: Int): Int =
            (hash + ((i * (i + 1)) ushr 1)) and (cap - 1)
    }

    // ─── abstract hooks for subclasses ───
    protected abstract fun makeInternalKey(userKey: K): IK
    protected abstract fun internalKeyEquals(a: IK, b: IK): Boolean
    protected abstract fun internalKeyHash(internalKey: IK): Int
    protected open fun onInsert(internalKey: IK, slot: Int) { }
    protected open fun onRemove(internalKey: IK, slot: Int) { }
    /** Primitive table-slot movement; rehash does not box one index pair per live key. */
    protected fun interface SlotRehash { fun move(from: Int, to: Int) }
    protected open fun onRehash(newCapacity: Int): SlotRehash? = null

     // ─── public API ───
    open val count: Int get() = size
    val totalCapacity: Int get() = capacity

    operator fun set(key: K, value: V): V? {
        val ik = makeInternalKey(key)
        val hash = mix(internalKeyHash(ik))
        var firstTomb = -1
        var i = 0
        // Triangular probing visits every slot in a power-of-two table. A fixed
        // probe cap rejects distinct filesystem paths with the same hash.
        while (i < capacity) {
            val slot = triangularProbe(hash, i, capacity)
            val k = keys[slot]
            when {
                isAbsent(k) -> {
                    if (size + tombstones >= capacity ushr 1) {
                        rehash(if (size >= capacity ushr 1) capacity shl 1 else capacity)
                        return set(key, value)
                    }
                    val ins = if (firstTomb >= 0) firstTomb else slot
                    keys[ins] = ik
                    values[ins] = value as Any?
                    size++
                    if (firstTomb >= 0) tombstones--
                    onInsert(ik, ins)
                    return null
                }
                isDeleted(k) -> {
                    if (firstTomb < 0) firstTomb = slot
                }
                internalKeyEquals(k as IK, ik) -> {
                    val old = values[slot] as V?
                    values[slot] = value as Any?
                    return old
                }
            }
            i++
        }
        error("LinearHashMap table full: capacity=$capacity size=$size")
    }

    operator fun get(key: K): V? {
        val ik = makeInternalKey(key)
        val hash = mix(internalKeyHash(ik))
        var i = 0
        while (i < capacity) {
            val slot = triangularProbe(hash, i, capacity)
            val k = keys[slot]
            when {
                isAbsent(k) -> return null
                !isDeleted(k) && internalKeyEquals(k as IK, ik) -> return values[slot] as V?
            }
            i++
        }
        return null
    }

    operator fun contains(key: K): Boolean = get(key) != null

    fun remove(key: K): V? {
        val ik = makeInternalKey(key)
        val hash = mix(internalKeyHash(ik))
        var i = 0
        while (i < capacity) {
            val slot = triangularProbe(hash, i, capacity)
            val k = keys[slot]
            when {
                isAbsent(k) -> return null
                !isDeleted(k) && internalKeyEquals(k as IK, ik) -> {
                    val old = values[slot] as V?
                    keys[slot] = DELETED
                    values[slot] = ABSENT
                    size--
                    tombstones++
                    onRemove(ik, slot)
                    return old
                }
            }
            i++
        }
        return null
    }

    /** Live entries as a frozen Series2 projection — `size j ::get` over the
     *  build buffer; no mutable surface leaves the map. Destructure with
     *  `val (k, v) = entry` or `.a`/`.b`. */
    fun entries(): Series2<K, V> {
        val result = SeriesBuffer<Join<K, V>>(size)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (!isAbsent(k) && !isDeleted(k)) {
                // subclasses must provide reverse mapping
                result += (extractUserKey(k as IK)) j (values[s] as V)
            }
        }
        return result.drain()
    }

    /** Frozen selection; nonmatching entries are never materialized. */
    fun entries(predicate: (K, V) -> Boolean): Series2<K, V> {
        val result = SeriesBuffer<Join<K, V>>()
        for (s in 0 until capacity) {
            val k = keys[s]
            if (isAbsent(k) || isDeleted(k)) continue
            @Suppress("UNCHECKED_CAST")
            val key = extractUserKey(k as IK)
            @Suppress("UNCHECKED_CAST")
            val value = values[s] as V
            if (predicate(key, value)) result += key j value
        }
        return result.drain()
    }

    protected abstract fun extractUserKey(internalKey: IK): K

    private fun rehash(newCap: Int) {
        val oldKeys   = keys
        val oldValues = values
        val oldCap    = capacity
        val moveMetadata = onRehash(newCap)
        capacity   = newCap
        keys       = newAbsentArray(newCap)
        values     = newAbsentArray(newCap)
        size       = 0
        tombstones = 0
        for (s in 0 until oldCap) {
            val k = oldKeys[s]
            if (!isAbsent(k) && !isDeleted(k)) {
                @Suppress("UNCHECKED_CAST")
                val slot = reinsert(k as IK, oldValues[s] as V)
                moveMetadata?.move(s, slot)
            }
        }
    }

    /** Place an existing key without minting insertion metadata; return its new slot. */
    private fun reinsert(ik: IK, value: V): Int {
        val hash = mix(internalKeyHash(ik))
        var i = 0
        while (i < capacity) {
            val slot = triangularProbe(hash, i, capacity)
            if (isAbsent(keys[slot])) {
                keys[slot] = ik
                values[slot] = value as Any?
                size++
                return slot
            }
            i++
        }
        error("LinearHashMap rehash failed: capacity=$capacity size=$size")
    }
}

/** LinearHashMap — the original mutable open-addressing map with K as internal key. */
class LinearHashMap<K : Any, V>(initialCapacity: Int = 16)
    : OpenAddressingMap<K, V, K>(initialCapacity) {

    override fun makeInternalKey(userKey: K): K = userKey
    override fun internalKeyEquals(a: K, b: K): Boolean = a == b
    override fun internalKeyHash(internalKey: K): Int = internalKey.hashCode()
    override fun extractUserKey(internalKey: K): K = internalKey
}

/** LinkedLinearHashMap — LinearHashMap preserving insertion order in primitive slot metadata.
 *
 *  Keys are stored directly; reads and removal never allocate or advance an order counter.
 *  Rehash moves the primitive order with its key. This is the ordered store behind
 *  [borg.trikeshed.kif.KifKnowledgeBase] (assert/retract by exact string,
 *  `toKifFile` in telling order), the same open-addressing shape
 *  [borg.trikeshed.dag.ReteWorkingMemory] keeps its facts in. Re-setting a
 *  present key keeps its position; a key removed and set again goes last. */
class LinkedLinearHashMap<K : Any, V>(initialCapacity: Int = 16)
    : OpenAddressingMap<K, V, K>(initialCapacity) {

    private var sequence: ULong = 0UL
    private var order = LongArray(capacity)

    override fun makeInternalKey(userKey: K): K = userKey

    override fun internalKeyEquals(a: K, b: K): Boolean = a == b

    override fun internalKeyHash(internalKey: K): Int = internalKey.hashCode()

    override fun extractUserKey(internalKey: K): K = internalKey

    override fun onInsert(internalKey: K, slot: Int) { order[slot] = sequence++.toLong() }

    override fun onRemove(internalKey: K, slot: Int) { order[slot] = 0L }

    override fun onRehash(newCapacity: Int): SlotRehash {
        val previous = order
        order = LongArray(newCapacity)
        return SlotRehash { from, to -> order[to] = previous[from] }
    }

    /** Iterate entries in insertion order (ascending counter) — frozen Series2. */
    fun entriesInOrder(): Series2<K, V> {
        val live = SeriesBuffer<Join<K, V>>(size)
        forEachInOrder { key, value -> live += key j value }
        return live.drain()
    }

    /** Select before sorting: allocation and ordering depend on matches, not the whole table. */
    fun entriesInOrder(predicate: (K, V) -> Boolean): Series2<K, V> {
        var heap = MinHeap(8)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (isAbsent(k) || isDeleted(k)) continue
            @Suppress("UNCHECKED_CAST")
            val key = k as K
            @Suppress("UNCHECKED_CAST")
            val value = values[s] as V
            if (predicate(key, value)) {
                if (heap.size == heap.capacity) heap = MinHeap(heap.slots.copyOf(1 + 4 * heap.capacity))
                heap.push(order[s], s.toLong())
            }
        }
        val live = SeriesBuffer<Join<K, V>>(heap.size)
        while (heap.size > 0) {
            val s = heap.minPayload.toInt()
            heap.popMin()
            @Suppress("UNCHECKED_CAST")
            live += (keys[s] as K) j (values[s] as V)
        }
        return live.drain()
    }

    /** Live entries in insertion order straight off the slot arrays: a [MinHeap] keyed by sequence, payload the slot. */
    fun forEachInOrder(block: (K, V) -> Unit) {
        val heap = MinHeap(size)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (isAbsent(k) || isDeleted(k)) continue
            heap.push(order[s], s.toLong())
        }
        while (heap.size > 0) {
            val s = heap.minPayload.toInt()
            heap.popMin()
            @Suppress("UNCHECKED_CAST")
            block(keys[s] as K, values[s] as V)
        }
    }
}
