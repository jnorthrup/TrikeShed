@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib


// inline factory value for CopyOnWriteSeries
inline val <reified T> Series<T>.cow: CowSeriesHandle<T> get() = CowSeriesHandle(COWSeriesBody(this))

/** Non-reified materialization — usable from generic functions. */
fun <T> Series<T>.materialize(): CowSeriesHandle<T> = CowSeriesHandle(COWSeriesBody(this))


/**
 * CopyOnWriteSeries which swaps a flat-array body on all mutation methods.
 *
 * Letter-envelope abstraction: the handle is the mutable envelope,
 * [COWSeriesBody] is the immutable letter backed by a contiguous [Array].
 *
 * JIT profile:
 *   - reads are O(1) aaload + checkcast (Graal inlines the lambda, BCEs the bounds, eliminates the cast)
 *   - mutations are O(n) arraycopy intrinsic (compiled to memcpy / vectorized copy)
 *   - single monomorphic receiver type at Function1.invoke (the body's b-lambda is one class, ever)
 *   - no closure nesting, no megamorphic dispatch, no staircase depth growth
 *   - trimToSize is automatic: every body carries an exact-sized array with zero spare capacity
 */
class CowSeriesHandle<T>(
    letter1: COWSeriesBody<T>,
    var observer: ((Twin<Series<T>>) -> Unit)? = null,
    var versionObserver: ((Twin<Long>) -> Unit)? = null,

    ) : MutableSeries<T> {

    var letter: COWSeriesBody<T> = letter1
        private set

    private fun swap(old: COWSeriesBody<T>, new: COWSeriesBody<T>) {
        letter = new
        observer?.invoke(old j new)
        versionObserver?.invoke(old.version j new.version)
    }

    override val a: Int get() = letter.a
    override val b: (Int) -> T get() = letter.b
    override fun set(index: Int, item: T) {
        swap(letter, letter.set(index, item))
    }

    override fun add(item: T) {
        swap(letter, letter.append(item))
    }

    override fun add(index: Int, item: T) {
        swap(letter, letter.insert(index, item))
    }

    override fun removeAt(index: Int): T {
        val item = letter[index]
        swap(letter, letter.removeAt(index))
        return item
    }

    override fun remove(item: T): Boolean {
        val i = letter.indexOf(item)
        if (i != -1) {
            swap(letter, letter.removeAt(i))
            return true
        }
        return false
    }

    override fun clear() {
        swap(letter, letter.clear())
    }

    override fun plus(item: T): MutableSeries<T> {
        add(item); return this
    }

    override fun minus(item: T): MutableSeries<T> {
        remove(item); return this
    }

    override fun plusAssign(item: T) {
        add(item)
    }

    override fun minusAssign(item: T) {
        remove(item)
    }

    operator fun get(range: IntRange): COWSeriesBody<T> = letter[range]

    val version: Long get() = letter.version
}


/**
 * Immutable COW letter backed by a flat Array.
 *
 * Every mutation returns a new body with a copied, exact-sized array.
 * No spare capacity — trimToSize is automatic.
 *
 * The [b] lambda is a stored val (one allocation at construction, captures the array reference).
 * Graal sees a single monomorphic receiver type at the invoke site → inlines → sees aaload → BCE.
 */
class COWSeriesBody<T>(
    private val buf: Array<Any?> = emptyArray(),
    override val a: Int = 0,
    override val version: Long = 0L,
) : Series<T>, VersionedSeries<T> {

    /** Materialize a lazy Series into a flat array. */
    constructor(backing: Series<T>) : this(
        Array(backing.size) { backing[it] },
        backing.size,
        0L
    )

    /** Single stored lambda — monomorphic at every invoke site. */
    override val b: (Int) -> T = { i: Int -> buf[i] as T }

    fun indexOf(item: T): Int {
        var i = 0
        while (i < a) {
            if (buf[i] == item) return i
            i++
        }
        return -1
    }

    /** O(n) arraycopy + single element replace. */
    fun set(index: Int, item: T): COWSeriesBody<T> {
        val newBuf = buf.copyOf()
        newBuf[index] = item
        return COWSeriesBody(newBuf, a, version + 1L)
    }

    /** O(n) arraycopy into a new array of size+1. */
    fun append(item: T): COWSeriesBody<T> {
        val newBuf = arrayOfNulls<Any?>(a + 1)
        buf.copyInto(newBuf, 0, 0, a)
        newBuf[a] = item
        return COWSeriesBody(newBuf, a + 1, version + 1L)
    }

    fun remove(item: T): COWSeriesBody<T> {
        val i = indexOf(item)
        return if (i != -1) removeAt(i) else this
    }

    /** O(n) arraycopy into a new array of size+1, shifting tail right. */
    fun insert(index: Int, item: T): COWSeriesBody<T> {
        val newBuf = arrayOfNulls<Any?>(a + 1)
        buf.copyInto(newBuf, 0, 0, index)
        newBuf[index] = item
        buf.copyInto(newBuf, index + 1, index, a)
        return COWSeriesBody(newBuf, a + 1, version + 1L)
    }

    /** O(n) arraycopy into a new array of size-1, shifting tail left. */
    fun removeAt(index: Int): COWSeriesBody<T> {
        val newBuf = arrayOfNulls<Any?>(a - 1)
        buf.copyInto(newBuf, 0, 0, index)
        buf.copyInto(newBuf, index, index + 1, a)
        return COWSeriesBody(newBuf, a - 1, version + 1L)
    }

    fun clear(): COWSeriesBody<T> = COWSeriesBody(version = version + 1L)

    operator fun get(range: IntRange): COWSeriesBody<T> {
        val len = range.last - range.first + 1
        val newBuf = arrayOfNulls<Any?>(len)
        buf.copyInto(newBuf, 0, range.first, range.last + 1)
        return COWSeriesBody(newBuf, len, version + 1L)
    }
}
