package borg.trikeshed.lib

/**
 * A MutableSeries that gates [add] and [set] operations behind a predicate.
 *
 * Mutations that violate the guard are silently ignored (add returns without
 * inserting, set leaves the element unchanged). [remove], [removeAt], and
 * [clear] always pass through.
 *
 * Delegates all other operations to the wrapped [MutableSeries] via `by`.
 *
 * @param guard  predicate: return true to allow the mutation
 * @param inner  the wrapped MutableSeries (default: fresh CowSeriesHandle)
 */
class GuardSeries<T>(
    private val guard: (T) -> Boolean,
    private val inner: MutableSeries<T> = CowSeriesHandle<T>(COWSeriesBody()),
) : MutableSeries<T> by inner {

    override fun add(item: T) {
        if (guard(item)) inner.add(item)
    }

    override fun add(index: Int, item: T) {
        if (guard(item)) inner.add(index, item)
    }

    override fun set(index: Int, item: T) {
        if (guard(item)) inner.set(index, item)
    }

    override fun plus(item: T): MutableSeries<T> {
        if (guard(item)) inner.plus(item)
        return this
    }

    override fun plusAssign(item: T) {
        if (guard(item)) inner.plusAssign(item)
    }
}
