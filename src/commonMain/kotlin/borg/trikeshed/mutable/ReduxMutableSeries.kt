package borg.trikeshed.mutable

import borg.trikeshed.lib.Reducer
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.getOrNull
import kotlin.jvm.JvmStatic

/**
 * A ReduxMutableSeries acts as an event log (a MutableSeries of Actions `A`)
 * that maintains a state `S` by applying a [Reducer].
 *
 * It allows "delayed reification" of the state, where the state is only evaluated
 * by folding the un-reified events upon access. It wraps any underlying
 * [MutableSeries] (such as JournalSeries or ChunkedMutableSeries) to
 * provide durable or in-memory layered event storage.
 */
class ReduxMutableSeries<A, S>(
    val eventJournal: MutableSeries<A>,
    val reducer: Reducer<A, S>,
    initialState: S = reducer.zero,
    /** Capture column reflecting the domain. A RowVec/FacetedRow IS the schema. */
    val capture: A,
) : MutableSeries<A> by eventJournal {

    private var _state: S = initialState
    private var _reifiedSize: Int = 0

    /**
     * Lazily materializes all unapplied events/actions into the current state.
     */
    fun reify(): S {
        var currentState = _state
        val currentSize = eventJournal.size
        if (_reifiedSize < currentSize) {
            for (i in _reifiedSize until currentSize) {
                val action = eventJournal.getOrNull(i)
                if (action != null) {
                    currentState = reducer.combine(currentState, action)
                }
            }
            _state = currentState
            _reifiedSize = currentSize
        }
        return currentState
    }

    /**
     * The lazily reified state.
     */
    val state: S
        get() = reify()

    /**
     * Syntactic sugar for appending a Redux action to the series.
     */
    fun dispatch(action: A) {
        eventJournal.add(action)
    }

    companion object {
        /**
         * Java-friendly factory that wraps an existing MutableSeries in a Redux journal
         * using a CollectorReducer.
         */
        @JvmStatic
        fun <T> of(delegate: MutableSeries<T>, capture: T): ReduxMutableSeries<T, Series<T>> {
            return ReduxMutableSeries(delegate, CollectorReducer(), capture = capture)
        }
    }
}