package borg.trikeshed.mutable

import borg.trikeshed.indicator.add
import borg.trikeshed.lib.plus

/**
 * A MutableSeries that gates [add] and [set] operations behind a predicate.
 *
 * Mutations that violate the guard are silently ignored (add returns without
 * inserting, set leaves the element unchanged). [remove], [removeAt], and
 * [clear] always pass through.
 *
 * Delegates all other operations to the wrapped [borg.trikeshed.mutable.MutableSeries] via `by`.
 *
 * @param guard  predicate: return true to allow the mutation
 * @param inner  the wrapped MutableSeries (default: fresh CowSeriesHandle)
 */
class GuardSeries<T>(
    private val guard: (T) -> Boolean,
    private val inner: MutableSeries<T> = CowSeriesHandle<T>(COWSeriesBody()),
) : Appendable<T>,  Insertable<T> by inner as Insertable<T> {

    override fun add(item: T) {
        if (guard(item)) inner.add(item)
    }

    override fun add(index: Int, item: T) {
        if (guard(item)) inner.add(index, item)
    }

    override fun set(index: Int, item: T) {
        if (guard(item)) inner.set(index, item)
    }

    fun plus(item: T): GuardSeries<T> {
        if (guard(item)) inner.plus(item)
        return this
    }

    fun plusAssign(item: T) {
        if (guard(item)) inner.plusAssign(item)
    }
}