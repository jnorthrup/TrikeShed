package borg.trikeshed.lib


/**
 * an immutable CopyOnWriteSwappingSeries (letter) which returns a new copy of itself on all add,set,append,remove,clear methods.
 *
 * this is the letter-and-envelope pattern for a mutable series.
 *
 * this contains a Long? immutable version attribute which is incremented during cloning, or stays null if the object-refernce is good enough for unordered version discriminator
 */
class CopyOnWriteSwappingSeries<T >(val backing: Array< T>, val version: Long? = null) : Series<T> {
    override val a: Int by backing::size
    override val b: (Int) -> T = backing::get

    /**
     * create a new copy of this, with the given item inserted at the given index
     */
    fun set(index: Int, item: T): CopyOnWriteSwappingSeries<T> {

        val newBacking = backing.copyOf()
        newBacking[index] = item
        return CopyOnWriteSwappingSeries(newBacking, version?.inc())
    }

    /**
     * create a new copy of this, with the given item appended
     */
    fun append(item: T) = copy(backing = backing + item)

    /**
     * create a new copy of this, with the given item removed
     */
    fun remove(item: T): CopyOnWriteSwappingSeries<T> {
        val i = backing.indexOf(item)
        if (i != -1) return copy(backing = backing.copyOf().apply { removeAt(i) })
        return this
    }

    fun insert(index: Int, item: T): CopyOnWriteSwappingSeries<T> {
        val t = backing.sliceArray(0 until 1)
        t[0] = item
        //slice to index
        val a = backing.sliceArray(0 until index)
        val b = backing.sliceArray(index until backing.size)
        return copy(backing = a + t + b)
    }

    /**
     * create a new copy of this, with the given item removed at the given index
     */
    fun removeAt(index: Int): CopyOnWriteSwappingSeries<T> {
        //make 2 slices of the array, then join them
        val a = backing.sliceArray(0 until index)
        val b = backing.sliceArray(index + 1 until backing.size)
        return copy(backing = a + b)

    }

    /**
     * create a new copy of this, with all items removed
     */
    fun clear(): CopyOnWriteSwappingSeries<T> {
        //create a new slice of 0
        return copy(backing = backing.copyOfRange(0, 0))

    }

    /**
     * create a new copy of this, with the given item inserted at the given index
     */
    fun copy(backing: Array<T> = this.backing, version: Long? = this.version?.inc()) =
        CopyOnWriteSwappingSeries(backing, version)
}


//factory method for CopyOnWriteSeries
inline val <reified T> Series<T>.cow get() = CopyOnWriteSeries(CopyOnWriteSwappingSeries(this.toArray()))