package borg.trikeshed.lib.mutable

import borg.trikeshed.lib.MutableSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * List-backed [MutableSeries] that IS-A [List].
 * Penalized by RLM like all mutables.
 *
 * Dual-implements MutableSeries + List so both `series[i]` and `list[i]` work
 * at IO edge boundaries. Since this IS-A List, toList() returns List with
 * zero type friction.
 */
class SeriesList<T>(
    private val backing: List<T> = ArrayList(),
) : MutableSeries<T>, List<T> {
    // MutableSeries: a is the size
    override val a: Int get() = backing.size
    override val b: (Int) -> T get() = { index -> backing[index] }

    // List requires size property
    override val size: Int get() = backing.size

    // MutableSeries mutation ops — delegate to backing
    override fun set(index: Int, item: T) { backing.set(index, item) }
    override fun add(item: T) { backing.add(item) }
    override fun add(index: Int, item: T) { backing.add(index, item) }
    override fun removeAt(index: Int): T = backing.removeAt(index)
    override fun remove(item: T): Boolean = backing.remove(item)
    override fun clear() { backing.clear() }
    override fun plusAssign(item: T) { backing.add(item) }
    override fun minusAssign(item: T) { backing.remove(item) }
    override fun plus(item: T): MutableSeries<T> { add(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }

    // List delegation to backing
    override fun get(index: Int): T = backing[index]
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun contains(element: T): Boolean = backing.contains(element)
    override fun containsAll(elements: Collection<out T>): Boolean = backing.containsAll(elements)
    override fun indexOf(element: T): Int = backing.indexOf(element)
    override fun lastIndexOf(element: T): Int = backing.lastIndexOf(element)
    override fun iterator(): Iterator<T> = backing.iterator()
    override fun listIterator(): ListIterator<T> = backing.listIterator()
    override fun listIterator(index: Int): ListIterator<T> = backing.listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): List<T> = backing.subList(fromIndex, toIndex)
    override fun hashCode(): Int = backing.hashCode()
    override fun equals(other: Any?): Boolean = backing.equals(other)
    override fun toString(): String = backing.toString()

    /** Snapshot to pure [Series]. */
    fun snapshot(): Series<T> = a j { index -> backing[index] }

    /** toList — backed by [Collection]. */
    fun toList(): List<T> = buildList { addAll(backing) }
}
