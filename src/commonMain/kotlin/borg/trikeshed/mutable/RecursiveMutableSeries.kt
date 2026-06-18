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

    override fun append(item: T) {
        val old = data
        val n = old.a
        data = (n + 1) j { i: Int ->
            if (i < n) old[i] else item
        }
    }

    override fun insert(index: Int, item: T) {
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

    // ── COW / freeze ─────────────────────────────────────────────

    override val isFrozen: Boolean get() = false

    override fun freeze(): Series<T> =
        FrozenArray<T>(Array<Any?>(data.a) { i -> data[i] })

    override fun cowSnapshot(): MutableSeries<T> = RecursiveMutableSeries(data)

    override fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit = {}

    override fun version(): Long = 0L

    // ── Iteration ────────────────────────────────────────────────

    override fun iterator(): Iterator<T> = sequence().iterator()

    override fun sequence(): Sequence<T> = Sequence { iterator() }

    // ── Concatenation ────────────────────────────────────────────

    override fun plus(other: MutableSeries<T>): MutableSeries<T> {
        val n = data.a
        val m = other.a
        return RecursiveMutableSeries(
            (n + m) j { i: Int ->
                if (i < n) data[i] else other.b(i - n)
            }
        )
    }

    override fun plus(item: T): MutableSeries<T> = RecursiveMutableSeries(data + s_[item])

    override fun minus(item: T): MutableSeries<T> = RecursiveMutableSeries(
        size j { i ->
            data[i].takeUnless { it == item } ?: data[i + 1]
        },
    )

    override fun plusAssign(item: T) {
        append(item)
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
