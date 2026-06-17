package borg.trikeshed.mutable

import borg.trikeshed.collections.s_
import borg.trikeshed.lib.*
import borg.trikeshed.lib.get

class RecursiveMutableSeries<T>(var data: Series<T>) : MutableSeries<T>, Series<T> {

    override fun set(index: Int, item: T) {
        val old = data
        data = old.a j { i: Int ->
            when (i) {
                index -> item
                else -> old[i]
            }
        }
    }

    override fun add(item: T) {
        val old = data
        val n = old.a
        data = (n + 1) j { i: Int ->
            if (i < n) old[i] else item
        }
    }

    override fun add(index: Int, item: T) {
        val old = data
        val n = old.a
        data = (n + 1) j { i: Int ->
            when {
                i > index -> old[i - 1]
                i < index -> old[i]
                else -> item
            }
        }
    }

    override fun removeAt(index: Int): T {
        val old = data
        val n = old.a
        val item = old[index]
        data = (n - 1) j { i: Int ->
            if (i < index) old[i] else old[i + 1]
        }
        return item
    }

    override fun remove(item: T) =
        this.view.withIndex().firstOrNull { it.value == item }?.index?.let { removeAt(it) } != null

    override fun clear() {
        data = emptySeriesOf<T>()
    }

    override fun plus(item: T): MutableSeries<T> = RecursiveMutableSeries(data + s_[item])

    override fun minus(item: T): MutableSeries<T> = RecursiveMutableSeries(
        size j { i ->
            data[i].takeUnless { it == item } ?: data[i + 1]
        },
    )

    override fun plusAssign(item: T) {
        add(item)
    }

    override fun minusAssign(item: T) {
        remove(item)
    }

    tailrec fun indexOfFirst(predicate: (T) -> Boolean, index: Int = 0): Int = when {
        index >= size -> -1
        predicate(data[index]) -> index
        else -> indexOfFirst(predicate, index + 1)
    }

    companion object {
        fun <T> create(initial: Series<T> = EmptySeries as Series<T>): RecursiveMutableSeries<T> =
            RecursiveMutableSeries(initial)
    }

    override val a: Int get() = data.a
    override val b: (Int) -> T get() = data.b
}