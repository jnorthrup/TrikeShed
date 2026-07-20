package borg.trikeshed.collections.associative

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Base class for open-addressing hash maps with triangular probing.
 * Subclasses provide the key representation and optional order tracking.
 *
 * Type parameters:
 *   K — user key type
 *   V — value type
 *   IK — internal key representation (K for LinearHashMap, Join<Int, ULong> for LinkedLinearHashMap)
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
    protected open fun onInsert(internalKey: IK) { }
    protected open fun onRemove(internalKey: IK) { }

     // ─── public API ───
    open val count: Int get() = size

    operator fun set(key: K, value: V): V? {
        if (size + tombstones >= capacity ushr 1) resize()
        val ik = makeInternalKey(key)
        val hash = internalKeyHash(ik)
        var firstTomb = -1
        var i = 0
        while (i <= capacity) {
            val slot = triangularProbe(hash, i, capacity)
            val k = keys[slot]
            when {
                isAbsent(k) -> {
                    val ins = if (firstTomb >= 0) firstTomb else slot
                    keys[ins] = ik
                    values[ins] = value as Any?
                    size++
                    if (firstTomb >= 0) tombstones--
                    onInsert(ik)
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
        resize()
        return set(key, value)
    }

    operator fun get(key: K): V? {
        val ik = makeInternalKey(key)
        val hash = internalKeyHash(ik)
        var i = 0
        while (i <= capacity) {
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
        val hash = internalKeyHash(ik)
        var i = 0
        while (i <= capacity) {
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
                    onRemove(ik)
                    return old
                }
            }
            i++
        }
        return null
    }

    fun entries(): List<Pair<K, V>> {
        val result = ArrayList<Pair<K, V>>(size)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (!isAbsent(k) && !isDeleted(k)) {
                // subclasses must provide reverse mapping
                result += (extractUserKey(k as IK)) to (values[s] as V)
            }
        }
        return result
    }

    protected abstract fun extractUserKey(internalKey: IK): K

    private fun resize() {
        val newCap = capacity shl 1
        val oldKeys   = keys
        val oldValues = values
        val oldCap    = capacity
        capacity   = newCap
        keys       = newAbsentArray(newCap)
        values     = newAbsentArray(newCap)
        size       = 0
        tombstones = 0
        for (s in 0 until oldCap) {
            val k = oldKeys[s]
            if (!isAbsent(k) && !isDeleted(k)) {
                set(extractUserKey(k as IK), oldValues[s] as V)
            }
        }
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

/** LinkedLinearHashMap — LinearHashMap preserving insertion order via Join<hash, counter>. */
class LinkedLinearHashMap<K : Any, V>(initialCapacity: Int = 16)
    : OpenAddressingMap<K, V, Join<Int, ULong>>(initialCapacity) {

    private var sequence: ULong = 0UL

    override fun makeInternalKey(userKey: K): Join<Int, ULong> =
        userKey.hashCode() j sequence++

    override fun internalKeyEquals(a: Join<Int, ULong>, b: Join<Int, ULong>): Boolean =
        a.a == b.a && a.b == b.b

    override fun internalKeyHash(internalKey: Join<Int, ULong>): Int = internalKey.a

    override fun extractUserKey(internalKey: Join<Int, ULong>): K =
        throw UnsupportedOperationException("LinkedLinearHashMap: reverse lookup not stored; use entriesInOrder()")

    /** Iterate entries in insertion order (ascending counter). */
    fun entriesInOrder(): List<Pair<K, V>> {
        // Collect live entries with their sequence counter, sort by counter
        val live = mutableListOf<Pair<ULong, Pair<K, V>>>()
        for (s in 0 until capacity) {
            val k = keys[s]
            if (!isAbsent(k) && !isDeleted(k)) {
                val ik = k as Join<Int, ULong>
                live += (ik.b) to (extractUserKeyByValue(values[s] as V) to values[s] as V)
            }
        }
        live.sortBy { it.first }
        return live.map { it.second }
    }

    /** Reverse lookup by value (for entriesInOrder) — override in subclass if needed. */
    protected open fun extractUserKeyByValue(value: V): K =
        throw UnsupportedOperationException("Override extractUserKeyByValue or store user key alongside")
}