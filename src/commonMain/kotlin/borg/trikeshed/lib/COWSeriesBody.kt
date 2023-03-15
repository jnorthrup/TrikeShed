package borg.trikeshed.lib

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
    override val version: Long? = null
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

