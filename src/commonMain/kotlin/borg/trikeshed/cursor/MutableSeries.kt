package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ── Mutable Series Variants ─────────────────────────────────────
//
// Mutation is a swap of the immutable letter inside a mutable envelope.
// The envelope itself is a MetaSeries.

/**
 * MutableSeries — the envelope interface.
 * Every mutable series IS a series with mutation ops.
 */
interface MutableSeries<T> : Join<Int, (Int) -> T> {
    val size: Int get() = a
    operator fun set(index: Int, item: T)
    fun add(item: T)
    fun removeAt(index: Int): T
}

// ── CowSeriesHandle ─────────────────────────────────────────────

/**
 * Copy-on-write with a flat Array body.
 *
 * Read: O(1), aaload + checkcast.
 * Write: O(n) arraycopy — the body is swapped atomically.
 * Observer fires old j new on every swap — the transition is a Twin.
 */
class CowSeriesHandle<T>(
    private var letter: CowSeriesBody<T>,
    private var observer: ((Twin<Series<T>>) -> Unit)? = null,
    private var versionObserver: ((Twin<Long>) -> Unit)? = null,
) : MutableSeries<T> {

    private var version: Long = 0L

    override val a: Int get() = letter.a
    override val b: (Int) -> T = { i -> letter.b(i) }

    override operator fun set(index: Int, item: T) {
        val old = letter
        letter = old.set(index, item)
        bumpVersion(old)
    }

    override fun add(item: T) {
        val old = letter
        letter = old.add(item)
        bumpVersion(old)
    }

    override fun removeAt(index: Int): T {
        val old = letter
        val removed = old.b(index)
        letter = old.removeAt(index)
        bumpVersion(old)
        return removed
    }

    private fun bumpVersion(old: CowSeriesBody<T>) {
        val oldVer = version
        version = oldVer + 1
        observer?.invoke(old j letter)
        versionObserver?.invoke(Twin(oldVer, version))
    }

    /**
     * Subscribe to copy-on-write snapshots.
     * @param f  called with Twin(oldLetter, newLetter) on every mutation
     * @return cancelable subscription — call .cancel() to unsubscribe
     */
    fun subscribe(f: (Twin<Series<T>>) -> Unit): () -> Unit {
        val prior = observer
        observer = f
        return { observer = prior }
    }

    /** Current version number. Increments on every mutation. */
    fun version(): Long = version
}

/**
 * CowSeriesBody — the immutable letter.
 * Backed by Array<Any?> — COW semantics via copy-on-mutation.
 */
class CowSeriesBody<T>(
    private val arr: Array<Any?>,
) : Join<Int, (Int) -> T> {
    override val a: Int get() = arr.size
    @Suppress("UNCHECKED_CAST")
    override val b: (Int) -> T = { i -> arr[i] as T }

    fun set(index: Int, item: T): CowSeriesBody<T> {
        val copy = arr.copyOf()
        copy[index] = item
        return CowSeriesBody(copy)
    }

    fun add(item: T): CowSeriesBody<T> {
        val copy = arr.copyOf(arr.size + 1)
        copy[arr.size] = item
        return CowSeriesBody(copy)
    }

    fun removeAt(index: Int): CowSeriesBody<T> {
        val copy = Array<Any?>(arr.size - 1) { i ->
            if (i < index) arr[i] else arr[i + 1]
        }
        return CowSeriesBody(copy)
    }

    companion object {
        fun <T> of(vararg items: T): CowSeriesBody<T> =
            CowSeriesBody(arrayOf(*items))
    }
}

// ── RingSeries ──────────────────────────────────────────────────

/**
 * Power-of-2 capacity, mask indexing.
 * All operations O(1). Overwrites when full.
 * Mask replaces modulo — single-cycle ALU op.
 */
class RingSeries<T>(capacity: Int) : MutableSeries<T> {
    init { require(capacity > 0 && (capacity and (capacity - 1)) == 0) { "capacity must be power of 2" } }

    private val mask = capacity - 1
    private val storage = arrayOfNulls<Any>(capacity)
    private var head = 0
    private var count = 0

    override val a: Int get() = count
    @Suppress("UNCHECKED_CAST")
    override val b: (Int) -> T = { i ->
        require(i in 0 until count) { "index $i out of bounds (size=$count)" }
        storage[(head + i) and mask] as T
    }

    override fun set(index: Int, item: T) {
        require(index in 0 until count)
        storage[(head + index) and mask] = item
    }

    override fun add(item: T) {
        val pos = (head + count) and mask
        storage[pos] = item
        if (count < mask + 1) count++ else head = (head + 1) and mask
    }

    override fun removeAt(index: Int): T {
        val item = b(index)
        // Shift elements left
        for (i in index until count - 1) {
            storage[(head + i) and mask] = storage[(head + i + 1) and mask]
        }
        count--
        return item
    }
}
