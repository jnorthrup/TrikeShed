package borg.trikeshed.mutable

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.get
import borg.trikeshed.cursor.Evidence

/**
 * A Decorator over an existing [MutableSeries] that acts as an Aspect-Oriented (AOP) pointcut harness.
 * Every mutating operation is intercepted, converted into a [MutationAction],
 * and dispatched to the provided sink (e.g. a ReduxMutableSeries "firehose")
 * BEFORE the mutation is applied to the underlying delegate.
 * 
 * Now carries confidence/errorMargin metadata for each mutation (aligned with
 * BlackboardOverlay.Evidence) to enable downstream confidence-weighted processing.
 */
class PointcutMutableSeries<T>(
    private val delegate: MutableSeries<T>,
    private val actionSink: (MutationAction<T>) -> Unit,
    // Optional confidence provider: if supplied, each mutation gets a confidence score
    private val confidenceProvider: ((MutationAction<T>) -> Evidence)? = null,
) : MutableSeries<T> {

    override val a: Int get() = delegate.a
    override val b: (Int) -> T get() = { delegate[it] }

    private fun emit(action: MutationAction<T>) {
        // Attach confidence if provider exists
        if (confidenceProvider != null) {
            val evidence = confidenceProvider!!(action)
            // Create new action with evidence attached - explicit construction to avoid type inference issues
            val withEvidence: MutationAction<T> = when (action) {
                is MutationAction.Set -> MutationAction.Set(action.index, action.item, evidence)
                is MutationAction.Add -> MutationAction.Add(action.item, evidence)
                is MutationAction.AddAtIndex -> MutationAction.AddAtIndex(action.index, action.item, evidence)
                is MutationAction.RemoveAt -> MutationAction.RemoveAt(action.index, evidence)
                is MutationAction.Remove -> MutationAction.Remove(action.item, evidence)
                is MutationAction.Clear -> MutationAction.Clear(evidence)
                is MutationAction.Plus -> MutationAction.Plus(action.item, evidence)
                is MutationAction.Minus -> MutationAction.Minus(action.item, evidence)
            }
            actionSink(withEvidence)
        } else {
            actionSink(action)
        }
    }

    override fun set(index: Int, item: T) {
        emit(MutationAction.Set(index, item))
        delegate[index] = item
    }

    override fun append(item: T) {
        emit(MutationAction.Add(item))
        delegate.append(item)
    }

    override fun insert(index: Int, item: T) {
        emit(MutationAction.AddAtIndex(index, item))
        delegate.insert(index, item)
    }

    override fun removeAt(index: Int): T {
        emit(MutationAction.RemoveAt(index))
        return delegate.removeAt(index)
    }

    override fun remove(item: T): Boolean {
        emit(MutationAction.Remove(item))
        return delegate.remove(item)
    }

    override fun clear() {
        emit(MutationAction.Clear())
        delegate.clear()
    }

    // ── COW / freeze ─────────────────────────────────────────────

    override val isFrozen: Boolean get() = delegate.isFrozen

    override fun freeze(): Series<T> = delegate.freeze()

    override fun cowSnapshot(): MutableSeries<T> =
        PointcutMutableSeries(delegate.cowSnapshot(), actionSink, confidenceProvider)

    override fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit =
        delegate.subscribe(observer)

    override fun version(): Long = delegate.version()

    // ── Iteration ────────────────────────────────────────────────

    override fun iterator(): Iterator<T> = delegate.iterator()

    override fun sequence(): Sequence<T> = delegate.sequence()

    // ── Concatenation ────────────────────────────────────────────

    override fun plus(other: MutableSeries<T>): MutableSeries<T> =
        PointcutMutableSeries(delegate.plus(other), actionSink, confidenceProvider)

    override fun plus(item: T): MutableSeries<T> {
        emit(MutationAction.Plus(item))
        return PointcutMutableSeries(delegate.plus(item), actionSink, confidenceProvider)
    }

    override fun minus(item: T): MutableSeries<T> {
        emit(MutationAction.Minus(item))
        return PointcutMutableSeries(delegate.minus(item), actionSink, confidenceProvider)
    }

    override fun plusAssign(item: T) {
        emit(MutationAction.Add(item)) // Logically equivalent
        delegate.plusAssign(item)
    }

    override fun minusAssign(item: T) {
        emit(MutationAction.Remove(item)) // Logically equivalent
        delegate.minusAssign(item)
    }
}