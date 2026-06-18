package borg.trikeshed.mutable

import borg.trikeshed.lib.get

/**
 * A Decorator over an existing [MutableSeries] that acts as an Aspect-Oriented (AOP) pointcut harness.
 * Every mutating operation is intercepted, converted into a [MutationAction],
 * and dispatched to the provided sink (e.g. a ReduxMutableSeries "firehose")
 * BEFORE the mutation is applied to the underlying delegate.
 */
class PointcutMutableSeries<T>(
    private val delegate: MutableSeries<T>,
    private val actionSink: (MutationAction<T>) -> Unit
) : Appendable<T>, RandomAccess<T>, Insertable<T>, Removable<T> {

    override val a: Int get() = delegate.a
    override val b: (Int) -> T get() = { delegate[it] }

    override fun set(index: Int, item: T) {
        actionSink(MutationAction.Set(index, item))
        delegate[index] = item
    }

    override fun add(item: T) {
        actionSink(MutationAction.Add(item))
        delegate.add(item)
    }

    override fun add(index: Int, item: T) {
        actionSink(MutationAction.AddAtIndex(index, item))
        delegate.add(index, item)
    }

    override fun removeAt(index: Int): T {
        actionSink(MutationAction.RemoveAt(index))
        return delegate.removeAt(index)
    }

    override fun remove(item: T): Boolean {
        actionSink(MutationAction.Remove(item))
        return delegate.remove(item)
    }

    override fun clear() {
        actionSink(MutationAction.Clear())
        delegate.clear()
    }

    fun plus(item: T): PointcutMutableSeries<T> {
        actionSink(MutationAction.Plus(item))
        return PointcutMutableSeries(delegate.plus(item), actionSink)
    }

    fun minus(item: T): PointcutMutableSeries<T> {
        actionSink(MutationAction.Minus(item))
        return PointcutMutableSeries(delegate.minus(item), actionSink)
    }

    fun plusAssign(item: T) {
        actionSink(MutationAction.Add(item)) // Logically equivalent
        delegate.plusAssign(item)
    }

    fun minusAssign(item: T) {
        actionSink(MutationAction.Remove(item)) // Logically equivalent
        delegate.minusAssign(item)
    }
}