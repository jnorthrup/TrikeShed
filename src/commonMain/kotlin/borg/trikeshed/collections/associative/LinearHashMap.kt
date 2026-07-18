@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections.associative

/**
 * LinearHashMap — open-addressing hash map with triangular probing.
 *
 * Probe sequence: h(k) + i*(i+1)/2 mod capacity.
 * Capacity is always a power of 2. With load ≤ 0.5 this sequence visits every
 * slot at most once before wrapping.
 *
 * Invariants:
 *   - capacity is a power of 2
 *   - load factor kept < 0.5 (resize at n >= capacity/2)
 *   - deleted tombstones (DELETED sentinel) allow probe chains to continue
 *   - tombstones are reclaimed on resize
 *
 * Cost: get/put/remove O(1) amortised; resize O(n) amortised.
 * KMP-compatible: pure commonMain, no reflection, no stdlib HashMap.
 *
 * IMPORTANT: keys/values arrays are *fresh* arrays pre-filled with TWO distinct
 * sentinel markers (ABSENT and DELETED). This avoids JS sparse-array quirks
 * where `arrayOfNulls(n)` may emit `undefined` slots that fail `=== null` checks
 * and cause unbounded probe loops on Node. Always check with `isAbsent(k)` /
 * `isDeleted(k)` rather than `=== null` / `=== DELETED`.
 */
class LinearHashMap<K : Any, V>(initialCapacity: Int = 16) {

    private var capacity: Int = nextPowerOfTwo(initialCapacity.coerceAtLeast(4))
    private var keys:   Array<Any?> = newAbsentArray(capacity)
    private var values: Array<Any?> = newAbsentArray(capacity)
    private var size: Int = 0
    private var tombstones: Int = 0

    companion object {
        private val DELETED = Any()   // tombstone sentinel
        private val ABSENT  = Any()   // empty-slot sentinel (target-stable across JVM/JS/Wasm)

        private fun nextPowerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }

        /**
         * Build a fresh Array<Any?> of size [n] where every slot holds [ABSENT].
         *
         * On JVM/JS/Wasm we cannot rely on `arrayOfNulls(n)` to leave slots
         * truly `null` — JS may emit sparse `undefined` slots, and the looser
         * `==` check is a footgun. Pre-filling with a stable marker makes
         * the "is this slot empty?" test a single `=== ABSENT` comparison.
         */
        private fun newAbsentArray(n: Int): Array<Any?> = Array(n) { ABSENT }

        private fun isAbsent(slotValue: Any?): Boolean = slotValue === ABSENT
        private fun isDeleted(slotValue: Any?): Boolean = slotValue === DELETED

        private fun triangularProbe(hash: Int, i: Int, capacity: Int): Int {
            // h + i*(i+1)/2  (mod capacity)
            // capacity is power-of-two so (x and (capacity-1)) == (x % capacity)
            return (hash + ((i * (i + 1)) ushr 1)) and (capacity - 1)
        }
    }

    val count: Int get() = size

    fun put(key: K, value: V): V? {
        if (size + tombstones >= capacity ushr 1) resize()   // load > 0.5
        val hash = key.hashCode()
        var firstTomb = -1
        var i = 0
        while (i <= capacity) {
            val slot = triangularProbe(hash, i, capacity)
            val k = keys[slot]
            when {
                isAbsent(k) -> {
                    val ins = if (firstTomb >= 0) firstTomb else slot
                    keys[ins] = key
                    values[ins] = value as Any?
                    size++
                    if (firstTomb >= 0) tombstones--
                    return null
                }
                isDeleted(k) -> {
                    if (firstTomb < 0) firstTomb = slot
                }
                k == key -> {
                    val old = values[slot] as V?
                    values[slot] = value as Any?
                    return old
                }
            }
            i++
        }
        // Probe exhausted — table misconfigured (load exceeded). Resize and retry.
        resize()
        return put(key, value)
    }

    fun get(key: K): V? {
        val hash = key.hashCode()
        var i = 0
        while (i <= capacity) {
            val slot = triangularProbe(hash, i, capacity)
            val k = keys[slot]
            when {
                isAbsent(k) -> return null
                !isDeleted(k) && k == key -> return values[slot] as V?
            }
            i++
        }
        return null
    }

    operator fun contains(key: K): Boolean = get(key) != null

    fun remove(key: K): V? {
        val hash = key.hashCode()
        var i = 0
        while (i <= capacity) {
            val slot = triangularProbe(hash, i, capacity)
            val k = keys[slot]
            when {
                isAbsent(k) -> return null
                !isDeleted(k) && k == key -> {
                    val old = values[slot] as V?
                    keys[slot] = DELETED
                    values[slot] = ABSENT
                    size--
                    tombstones++
                    return old
                }
            }
            i++
        }
        return null
    }

    /** Returns all live (key, value) pairs as a List. O(capacity). */
    fun entries(): List<Pair<K, V>> {
        val result = ArrayList<Pair<K, V>>(size)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (!isAbsent(k) && !isDeleted(k))
                result += (k as K) to (values[s] as V)
        }
        return result
    }

    /** Returns all live keys as a List. O(capacity). */
    fun keyList(): List<K> {
        val result = ArrayList<K>(size)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (!isAbsent(k) && !isDeleted(k)) result += k as K
        }
        return result
    }

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
            if (!isAbsent(k) && !isDeleted(k)) put(k as K, oldValues[s] as V)
        }
    }
}
