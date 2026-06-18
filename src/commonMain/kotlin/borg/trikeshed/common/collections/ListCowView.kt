package borg.trikeshed.common.collections

/** a mutable listView of a List which performs a copy to MutableList on first mutation. Not threadsafe. */
class ListCowView<T>(private var list: List<T> = emptyList()) : List<T>, AbstractMutableList<T>() {

    private fun ensureMutable() {
        if (list !is MutableList<T>) {
            list = list.toMutableList()
        }
    }

    override fun add(index: Int, element: T) {
        ensureMutable()
        (list as MutableList<T>).add(index, element)
    }

    override val size: Int get() = list.size

    override fun get(index: Int): T = list[index]

    override fun removeAt(index: Int): T {
        ensureMutable()
        return (list as MutableList<T>).removeAt(index)
    }

    override fun set(index: Int, element: T): T {
        ensureMutable()
        return (list as MutableList<T>).set(index, element)
    }

    override fun toString(): String = "ListCowView(list=$list)"
}
