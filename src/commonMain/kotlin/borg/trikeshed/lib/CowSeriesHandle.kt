package borg.trikeshed.lib

import kotlin.properties.Delegates


// inline factory value for CopyOnWriteSeries
inline val <reified T> Series<T>.cow: CowSeriesHandle<T> get() = CowSeriesHandle(COWSeriesBody(this.toList()))


/**
 * CopyOnWriteSeries which creates a new copy of the backing Series on all mutation methods.
 *
 * this the envelope of a letter+envelope abstraction using CopyOnWriteSwappingSeries as the letter
 *
 * this contains a Long? immutable version attribute which is incremented during cloning, or stays null
 *
 */
class CowSeriesHandle<T>(
    letter1: COWSeriesBody<T>,
    var observer: ((Twin<Series<T>>) -> Unit)? = null,
    var versionObserver: ((Twin<Long?>) -> Unit)? = null,

    ) : MutableSeries<T> {

    var letter: COWSeriesBody<T> by Delegates.observable(letter1) { _, old, new ->
        observer?.invoke(old j new)
    }

    override val a: Int get() = letter.a
    override val b: (Int) -> T get() = letter.b
    override fun set(index: Int, item: T) {
        letter = letter.set(index, item)
    }

    override fun add(item: T) {
        letter = letter.append(item)
    }

    override fun add(index: Int, item: T) {
        letter = letter.insert(index, item)
    }

    override fun removeAt(index: Int): T {
        val item = letter.b(index)
        letter = letter.removeAt(index)
        return item
    }

    override fun remove(item: T): Boolean {
        val i = letter.backing.indexOf(item)
        if (i != -1) {
            letter = letter.removeAt(i)
            return true
        }
        return false
    }

    override fun clear() {
        letter = letter.clear()
    }

    override fun append(item: T) {
        letter = letter.append(item)
    }

    override fun plus(item: T): MutableSeries<T> {
        letter = letter.append(item); return this
    }

    override fun minus(item: T): MutableSeries<T> {
        letter = letter.remove(item); return this
    }

    override fun plusAssign(item: T) {
        letter = letter.append(item)
    }

    override fun minusAssign(item: T) {
        letter = letter.remove(item)
    }

    operator fun get(range: IntRange): COWSeriesBody<T> = letter.get(range)

    //version from backing
    val version: Any get() = letter.version ?: letter.toString()
}


/**
 * an immutable CopyOnWriteSwappingSeries (letter) which returns a new copy of itself on all add,set,append,remove,clear methods.
 *
 * this is the letter-and-envelope pattern for a mutable series.
 *
 * this contains a Long? immutable version attribute which is incremented during cloning, or stays null if the
 * object-identity is good enough for unordered version discriminator
 */
class COWSeriesBody<T>(
    val backing: List<T> = emptyList(),
    override val version: Long? = null,
) : Series<T>, VersionedSeries {
    override val a: Int by backing::size
    override val b: (Int) -> T = backing::get

    /** create a new copy of this, with the given item inserted at the given index */
    fun set(index: Int, item: T): COWSeriesBody<T> {
        //use List(size){x} ctor here to avoid copying the backing list
        return copy(List(a) { i -> if (i == index) item else backing[i] }, version?.inc())
    }

    /** create a new copy of this, with the given item appended */
    fun append(item: T): COWSeriesBody<T> = copy(backing = backing + item)

    /** create a new copy of this, with the given item removed */
    fun remove(item: T): COWSeriesBody<T> {

        return copy(backing.filterNot { item == it })

    }

    fun insert(index: Int, item: T): COWSeriesBody<T> = copy(backing = List(backing.size.inc()) {
        when {
            it < index -> backing[it]
            it > index -> backing[it.dec()]
            else -> item
        }
    })


    /** create a new copy of this, with the given item removed at the given index */
    fun removeAt(index: Int): COWSeriesBody<T> = copy(backing = List(backing.size.dec()) {
        when {
            it < index -> backing[it]
            else -> backing[it.inc()]
        }
    })


    fun clear(): COWSeriesBody<T> = /*create a new slice of 0*/ copy(backing = backing.subList(0, 0))
    operator fun get(range: IntRange): COWSeriesBody<T> = copy(backing = backing.slice(range))

    /** create a new copy of this, with the given item inserted at the given index */
    private fun copy(backing: List<T> = this.backing, version: Long? = this.version?.inc()) =
        COWSeriesBody(backing, version)
}

