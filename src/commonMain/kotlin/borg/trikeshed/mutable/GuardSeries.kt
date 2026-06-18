package borg.trikeshed.mutable

/**
 * A MutableSeries that gates [append] and [set] operations behind a predicate.
 *
 * Mutations that violate the guard are silently ignored (append returns without
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
    private val inner: MutableSeries<T> = CowSeriesHandle<T>(),
) : MutableSeries<T> by inner {

    override fun append(item: T) {
        if (guard(item)) inner.append(item)
    }

    override fun insert(index: Int, item: T) {
        if (guard(item)) inner.insert(index, item)
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
