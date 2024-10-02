package borg.trikeshed.lib

import borg.trikeshed.common.collections.s_

class RecursiveMutableSeries<T> private constructor(private var data: Series<T>) : MutableSeries<T>, Series<T>  {

    override fun set(index: Int, item: T) {
        data = size j { i: Int -> when(i) {
            index -> item
            else -> data[i]
        }}
    }

    override fun add(item: T) {
        data = size j { i: Int -> when {
            i == size -> item
            else -> data[i]
        }}
    }

    override fun add(index: Int, item: T) {
        data = size j { i: Int -> when {
            i > index -> data[i - 1]
            i < index -> data[i]
            else -> item
        }}
    }

    override fun removeAt(index: Int): T = data[index].also {
        data = size j { i: Int -> when {
            i < index -> data[i]
            i >= index && i < size - 1 -> data[i + 1]
            else -> data[i]
        }}
    }

    override fun remove(item: T) =  this.`▶` .withIndex().firstOrNull { it.value == item }?.index?.let { removeAt(it) } != null

    override fun clear() {
        data = EmptySeries as Series<T>
    }

    override fun plus(item: T): MutableSeries<T> = RecursiveMutableSeries(data + s_[item])

    override fun minus(item: T): MutableSeries<T> = RecursiveMutableSeries(size j { i ->
        data[i].takeUnless { it == item } ?: data[i + 1]
    })

    override fun plusAssign(item: T) {
        add(item)
    }

    override fun minusAssign(item: T) {
        remove(item)
    }


    private tailrec fun indexOfFirst(predicate: (T) -> Boolean, index: Int = 0): Int = when {
        index >= size -> -1
        predicate(data[index]) -> index
        else -> indexOfFirst(predicate, index + 1)
    }

    companion object {
        fun <T> create(initial: Series<T> = EmptySeries as Series<T>): RecursiveMutableSeries<T> =
            RecursiveMutableSeries(initial)
    }

    override val a: Int by data::a
    override val b: (Int) -> T  by data::b
}