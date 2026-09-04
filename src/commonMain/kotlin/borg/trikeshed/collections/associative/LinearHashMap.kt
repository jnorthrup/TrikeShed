package borg.trikeshed.collections.associative

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
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
        protected const val MAX_PROBES = 32
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
    protected open fun onInsert(internalKey: IK) { }
    protected open fun onRemove(internalKey: IK) { }

     // ─── public API ───
    open val count: Int get() = size

    operator fun set(key: K, value: V): V? {
        if (size + tombstones >= capacity ushr 1) resize()
        val ik = makeInternalKey(key)
        val hash = mix(internalKeyHash(ik))
        var firstTomb = -1
        var i = 0
        while (i < MAX_PROBES) {
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
        throw IllegalStateException("LinearHashMap set() exhausted: probes=$MAX_PROBES size=$size hash=${key.hashCode()}")
    }

    operator fun get(key: K): V? {
        val ik = makeInternalKey(key)
        val hash = mix(internalKeyHash(ik))
        var i = 0
        while (i < MAX_PROBES) {
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
        while (i < MAX_PROBES) {
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

    /** Live entries as a frozen Series2 projection — `size j ::get` over the
     *  build buffer; no mutable surface leaves the map. Destructure with
     *  `val (k, v) = entry` or `.a`/`.b`. */
    fun entries(): Series2<K, V> {
        val result = ArrayList<Join<K, V>>(size)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (!isAbsent(k) && !isDeleted(k)) {
                // subclasses must provide reverse mapping
                result += (extractUserKey(k as IK)) j (values[s] as V)
            }
        }
        return result.size j result::get
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
                @Suppress("UNCHECKED_CAST")
                reinsert(k as IK, oldValues[s] as V)
            }
        }
    }

    /** Place an existing internal key into the grown table unchanged — a
     *  subclass that carries order in its internal key (LinkedLinearHashMap)
     *  keeps that order across a resize; `set` would mint a new one. */
    private fun reinsert(ik: IK, value: V) {
        val hash = mix(internalKeyHash(ik))
        var i = 0
        while (i < MAX_PROBES) {
            val slot = triangularProbe(hash, i, capacity)
            if (isAbsent(keys[slot])) {
                keys[slot] = ik
                values[slot] = value as Any?
                size++
                return
            }
            i++
        }
        throw IllegalStateException("LinearHashMap resize() exhausted: probes=$MAX_PROBES size=$size")
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

/** LinkedLinearHashMap — LinearHashMap preserving insertion order via Join<hash, counter>.
 *
 *  Delta (2026-09-04): the internal key is now `Join<userKey, counter>` and two
 *  internal keys are equal when their USER keys are equal — the counter only
 *  orders. Before this the counter took part in equality, so `get`/`remove`
 *  minted a fresh counter and never matched a stored key: the map was
 *  write-only and had no callers. It is now the ordered keyed store behind
 *  [borg.trikeshed.kif.KifKnowledgeBase] (assert/retract by exact string,
 *  `toKifFile` in telling order), the same open-addressing shape
 *  [borg.trikeshed.dag.ReteWorkingMemory] keeps its facts in. Re-setting a
 *  present key keeps its position; a key removed and set again goes last. */
class LinkedLinearHashMap<K : Any, V>(initialCapacity: Int = 16)
    : OpenAddressingMap<K, V, Join<K, ULong>>(initialCapacity) {

    private var sequence: ULong = 0UL

    override fun makeInternalKey(userKey: K): Join<K, ULong> =
        userKey j sequence++

    override fun internalKeyEquals(a: Join<K, ULong>, b: Join<K, ULong>): Boolean =
        a.a == b.a

    override fun internalKeyHash(internalKey: Join<K, ULong>): Int = internalKey.a.hashCode()

    override fun extractUserKey(internalKey: Join<K, ULong>): K = internalKey.a

    /** Iterate entries in insertion order (ascending counter) — frozen Series2. */
    fun entriesInOrder(): Series2<K, V> {
        // Collect live entries with their sequence counter, sort by counter
        val live = ArrayList<Join<ULong, Join<K, V>>>(size)
        for (s in 0 until capacity) {
            val k = keys[s]
            if (!isAbsent(k) && !isDeleted(k)) {
                @Suppress("UNCHECKED_CAST")
                val ik = k as Join<K, ULong>
                @Suppress("UNCHECKED_CAST")
                live += ik.b j (ik.a j (values[s] as V))
            }
        }
        live.sortBy { it.a }
        return live.size j { i: Int -> live[i].b }
    }
}
