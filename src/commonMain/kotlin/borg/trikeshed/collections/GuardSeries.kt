package borg.trikeshed.collections

/**
 * A MutableSeries that gates every insertion — [append], [insert], [set] and their spellings [add] and `+=` —
 * behind a predicate.
 *
 * Mutations that violate the guard are silently ignored (append returns without
 * inserting, set leaves the element unchanged). [remove], [removeAt], and
 * [clear] always pass through.
 *
 * Delegates all other operations to the wrapped [borg.trikeshed.collections.MutableSeries] via `by`. Delegation
 * forwards the interface's default methods too, so each spelling of an insertion is overridden here, or it would
 * reach [inner] unguarded.
 *
 * @param guard  predicate: return true to allow the mutation
 * @param inner  the wrapped MutableSeries (default: fresh COWArrayBackend)
 */
class GuardSeries<T>(
    val guard: (T) -> Boolean,
    val inner: MutableSeries<T> = COWArrayBackend<T>(),
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

    override fun add(item: T) = append(item)

    override fun add(index: Int, item: T) = insert(index, item)

    override fun plusAssign(item: T) = append(item)
}
