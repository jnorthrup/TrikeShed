@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

/**
 * LinearHashMap — open-addressing hash map with triangular (quadratic) probing.
 *
 * Probe sequence: h(k) + i*(i+1)/2 mod capacity.
 * Capacity is always a power of 2. With load ≤ 0.5 this sequence visits every
 * slot exactly once before wrapping (arXiv:2501.02305 §3).
 *
 * Invariants:
 *   - capacity is a power of 2
 *   - load factor kept < 0.5 (resize at n >= capacity/2)
 *   - deleted tombstones (DELETED sentinel) allow probe chains to continue
 *   - tombstones are reclaimed on resize
 *
 * Cost: get/put/remove O(1) amortised; resize O(n) amortised.
 * KMP-compatible: pure commonMain, no reflection, no stdlib HashMap.
 */
class LinearHashMap<K : Any, V>(initialCapacity: Int = 16) {

    private var capacity: Int = nextPowerOfTwo(initialCapacity.coerceAtLeast(4))
    private var keys:   Array<Any?> = arrayOfNulls(capacity)
    private var values: Array<Any?> = arrayOfNulls(capacity)
    private var size: Int = 0
    private var tombstones: Int = 0

    companion object {
        private val DELETED = Any()   // tombstone sentinel

        private fun nextPowerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }

        private fun triangularProbe(hash: Int, i: Int, capacity: Int): Int {
            // h + i*(i+1)/2  (mod capacity)
            // capacity is power-of-2 so (x and (capacity-1)) == (x % capacity)
            return (hash + ((i * (i + 1)) ushr 1)) and (capacity - 1)
        }
    }

    val count: Int get() = size

    fun put(key: K, value: V): V? {
        if (size + tombstones >= capacity ushr 1) resize()   // load > 0.5
        val hash = key.hashCode()
        var firstTomb = -1
        var i = 0
        while (true) {
            val slot = triangularProbe(hash, i, capacity)
            when {
                keys[slot] === null -> {
                    val ins = if (firstTomb >= 0) firstTomb else slot
                    keys[ins] = key
                    values[ins] = value as Any?
                    size++
                    if (firstTomb >= 0) tombstones--
                    return null
                }
                keys[slot] === DELETED -> {
                    if (firstTomb < 0) firstTomb = slot
                }
                keys[slot] == key -> {
                    val old = values[slot] as V?
                    values[slot] = value as Any?
                    return old
                }
            }
            i++
        }
    }

    fun get(key: K): V? {
        val hash = key.hashCode()
        var i = 0
        while (true) {
            val slot = triangularProbe(hash, i, capacity)
            when {
                keys[slot] === null -> return null
                keys[slot] !== DELETED && keys[slot] == key -> return values[slot] as V?
            }
            i++
        }
    }

    operator fun contains(key: K): Boolean = get(key) != null

    fun remove(key: K): V? {
        val hash = key.hashCode()
        var i = 0
        while (true) {
            val slot = triangularProbe(hash, i, capacity)
            when {
                keys[slot] === null -> return null
                keys[slot] !== DELETED && keys[slot] == key -> {
                    val old = values[slot] as V?
                    keys[slot] = DELETED
                    values[slot] = null
                    size--
                    tombstones++
                    return old
                }
            }
            i++
        }
    }

    /** Returns all live (key, value) pairs as a List. O(capacity). */
    fun entries(): List<Pair<K, V>> {
        val result = ArrayList<Pair<K, V>>(size)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (k !== null && k !== DELETED)
                result += (k as K) to (values[s] as V)
        }
        return result
    }

    /** Returns all live keys as a List. O(capacity). */
    fun keyList(): List<K> {
        val result = ArrayList<K>(size)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (k !== null && k !== DELETED) result += k as K
        }
        return result
    }

    private fun resize() {
        val newCap = capacity shl 1
        val oldKeys   = keys
        val oldValues = values
        val oldCap    = capacity
        capacity   = newCap
        keys       = arrayOfNulls(newCap)
        values     = arrayOfNulls(newCap)
        size       = 0
        tombstones = 0
        for (s in 0 until oldCap) {
            val k = oldKeys[s]
            if (k !== null && k !== DELETED) put(k as K, oldValues[s] as V)
        }
    }
}
