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
    val backing: Array<T>,
    override val version: Long? = null
) : Series<T>, VersionedSeries {
    override val a: Int by backing::size
    override val b: (Int) -> T = backing::get

    /** create a new copy of this, with the given item inserted at the given index */
    fun set(index: Int, item: T): COWSeriesBody<T> {
        val newBacking = backing.copyOf()
        newBacking[index] = item
        return COWSeriesBody(newBacking, version?.inc())
    }

    /** create a new copy of this, with the given item appended */
    fun append(item: T) = copy(backing = backing + item)

    /** create a new copy of this, with the given item removed */
    fun remove(item: T): COWSeriesBody<T> {
        val i = backing.indexOf(item)
        if (i != -1) return copy(backing = backing.copyOf().apply { removeAt(i) })
        return this
    }

    fun insert(index: Int, item: T): COWSeriesBody<T> {
        val t = backing.sliceArray(0 until 1)
        t[0] = item
        //slice to index
        val a = backing.sliceArray(0 until index)
        val b = backing.sliceArray(index until backing.size)
        return copy(backing = a + t + b)
    }

    /** create a new copy of this, with the given item removed at the given index */
    fun removeAt(index: Int): COWSeriesBody<T> {
        //make 2 slices of the array, then join them
        val a = backing.sliceArray(0 until index)
        val b = backing.sliceArray(index + 1 until backing.size)
        return copy(backing = a + b)

    }

    /** create a new copy of this, with all items removed */
    fun clear(): COWSeriesBody<T> = /*create a new slice of 0*/ copy(backing = backing.copyOfRange(0, 0))
    operator fun get(range: IntRange): COWSeriesBody<T> =
        copy(backing = backing.sliceArray(range))

    /** create a new copy of this, with the given item inserted at the given index */
    private fun copy(backing: Array<T> = this.backing, version: Long? = this.version?.inc()) =
        COWSeriesBody(backing, version)
}
