package borg.trikeshed.lib.collections

import borg.trikeshed.lib.*

class SortedSetImpl<T : Any>(val c: Comparator<T>, val cowSeriesHandle: CowSeriesHandle<T>) : SortedSet<T>, MutableSet<T>   {

    inline fun <reified T> convert(toSeries: Join<Int, (Int) -> T>) = toSeries.cow as MutableSeries<T>

    companion object {
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T : Any> of(c: Comparator<T>, vararg items: T): SortedSetImpl<T> {
            val series = (items).sortedArrayWith(c as Comparator<in T>).toSeries() as Series<T>
            val cow = series.cow
            return SortedSetImpl(c, cow)
        }

        inline fun <reified T : Any> of(c: Comparator<T>, items: List<T>): SortedSetImpl<T> {
            val series = items.sortedWith(c).toSeries()
            val cow = series.cow
          return SortedSetImpl(c, cow)
        }

    }

    override fun add(element: T): Boolean {
        val binarysearch = cowSeriesHandle.binarysearch(element, c)
        if (binarysearch >= 0) return false
        cowSeriesHandle.add(-binarysearch - 1, element)
        return true
    }

    override fun addAll(elements: Collection<T>): Boolean {
        var changed = false
        for (element in elements) {
            changed = add(element) || changed
        }
        return changed
    }

    override fun clear() {
        cowSeriesHandle.clear()
    }

    override fun iterator(): MutableIterator<T> {
        return cowSeriesHandle.iterator() as MutableIterator<T>
    }

    override fun remove(element: T): Boolean {
        val binarysearch = cowSeriesHandle.binarysearch(element, c)
        if (binarysearch < 0) return false
        cowSeriesHandle.removeAt(binarysearch)
        return true
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        var changed = false
        for (element in elements) {
            changed = remove(element) || changed
        }
        return changed
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        var changed = false
        for (element in cowSeriesHandle) {
            if (!elements.contains(element)) {
                remove(element)
                changed = true
            }
        }
        return changed
    }

    override val size: Int get() = cowSeriesHandle.size

    override fun contains(element: T): Boolean {
        return cowSeriesHandle.binarysearch(element, c) >= 0
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        for (element in elements) {
            if (!contains(element)) return false
        }
        return true
    }

    override fun isEmpty(): Boolean {
        return cowSeriesHandle.isEmpty()
    }

    override fun toString(): String {
        return cowSeriesHandle.toString()
    }

    override fun equals(other: Any?): Boolean {
        when {
            this === other -> return true
            other !is Set<*> -> return false
            size != other.size -> return false
            else -> return containsAll(other)
        }
    }

    override fun hashCode(): Int {
        var result = 0
        for (element in this) result += element.hashCode()
        return result
    }
}