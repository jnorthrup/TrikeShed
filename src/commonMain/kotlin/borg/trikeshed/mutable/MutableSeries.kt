@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j

/**
 * MutableSeries — the canonical envelope interface.
 *
 * Every mutable series IS a [Series] (the kernel `Join<Int, (Int) -> T>`) augmented
 * with mutation operators. This is the single contract implemented by every strategy
 * in [borg.trikeshed.mutable] and [borg.trikeshed.mutableseries].
 *
 * This is the consolidated home for the type formerly split across `lib/` (the rich,
 * operator-overload MutableSeries) and `cursor/` (the thin 3-method variant). The
 * full operator surface is retained so every migrated strategy compiles unchanged.
 */
interface MutableSeries<T> : Series<T> {
    /** Logical element count. Defaults to [Series.a] (the join arity). */
    val size: Int get() = a

    operator fun set(index: Int, item: T)
    fun add(item: T)
    fun add(index: Int, item: T)
    fun removeAt(index: Int): T
    fun remove(item: T): Boolean
    fun clear()

    operator fun plus(item: T): MutableSeries<T>
    operator fun minus(item: T): MutableSeries<T>
    operator fun plusAssign(item: T)
    operator fun minusAssign(item: T)
}

/**
 * Copy-on-write envelope with a flat [CowSeriesBody] letter.
 *
 * Read: O(1), aaload + checkcast.
 * Write: O(n) arraycopy — the body is swapped atomically.
 * Observer fires `old j new` on every swap — the transition is a [Twin].
 *
 * Implements the full [MutableSeries] contract; mutations delegate to the immutable
 * letter and bump an internal version, notifying any subscribed observer.
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

    override fun add(index: Int, item: T) {
        val old = letter
        letter = old.insert(index, item)
        bumpVersion(old)
    }

    override fun removeAt(index: Int): T {
        val old = letter
        val removed = old.b(index)
        letter = old.removeAt(index)
        bumpVersion(old)
        return removed
    }

    override fun remove(item: T): Boolean {
        val old = letter
        val i = (0 until old.a).firstOrNull { old.b(it) == item } ?: return false
        letter = old.removeAt(i)
        bumpVersion(old)
        return true
    }

    override fun clear() {
        val old = letter
        letter = old.clear()
        bumpVersion(old)
    }

    override fun plus(item: T): MutableSeries<T> { add(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }
    override fun plusAssign(item: T) { add(item) }
    override fun minusAssign(item: T) { remove(item) }

    private fun bumpVersion(old: CowSeriesBody<T>) {
        val oldVer = version
        version = oldVer + 1
        observer?.invoke(old j letter)
        versionObserver?.invoke(Twin(oldVer, version))
    }

    /**
     * Subscribe to copy-on-write snapshots.
     * @param f  called with Twin(oldLetter, newLetter) on every mutation
     * @return cancelable subscription — invoke to restore the prior observer
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
 *
 * Backed by `Array<Any?>` — copy-on-write semantics: every mutator returns a fresh
 * body with a copied array. The [CowSeriesHandle] envelope swaps the letter
 * atomically on every mutation.
 */
class CowSeriesBody<T>(
    private val arr: Array<Any?> = emptyArray(),
) : Join<Int, (Int) -> T> {
    override val a: Int get() = arr.size
    @Suppress("UNCHECKED_CAST")
    override val b: (Int) -> T = { i -> arr[i] as T }

    fun set(index: Int, item: T): CowSeriesBody<T> {
        val copy = arr.copyOf()
        copy[index] = item
        return CowSeriesBody(copy)
    }

    /** Append at the tail. */
    fun add(item: T): CowSeriesBody<T> = insert(arr.size, item)

    /** Insert at [index], shifting the tail right. */
    fun insert(index: Int, item: T): CowSeriesBody<T> {
        val copy = Array<Any?>(arr.size + 1) { i ->
            when {
                i < index -> arr[i]
                i == index -> item
                else -> arr[i - 1]
            }
        }
        return CowSeriesBody(copy)
    }

    fun removeAt(index: Int): CowSeriesBody<T> {
        val copy = Array<Any?>(arr.size - 1) { i ->
            if (i < index) arr[i] else arr[i + 1]
        }
        return CowSeriesBody(copy)
    }

    /** Remove the first element equal to [item]; returns this unchanged if absent. */
    fun remove(item: T): CowSeriesBody<T> {
        val i = arr.indexOfFirst { it == item }
        return if (i >= 0) removeAt(i) else this
    }

    fun clear(): CowSeriesBody<T> = CowSeriesBody(emptyArray())

    companion object {
        fun <T> of(vararg items: T): CowSeriesBody<T> =
            CowSeriesBody(arrayOf(*items))
    }
}

/**
 * Canonical (all-caps) spelling retained for source compatibility with the `lib/`
 * lineage. [COWSeriesBody] and [CowSeriesBody] denote the same type, so legacy
 * call sites such as `COWSeriesBody()` resolve to an empty letter.
 */
typealias COWSeriesBody<T> = CowSeriesBody<T>
