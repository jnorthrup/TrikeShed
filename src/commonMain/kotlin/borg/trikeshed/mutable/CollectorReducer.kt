@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.*

/**
 * CollectorReducer — a generic [Reducer] that accumulates dispatched
 * elements into a [Series] by appending.
 *
 * This is the "identity" reducer for [ReduxMutableSeries]: every dispatched
 * action is simply collected into the state as a growing series, providing
 * a complete emission journal.
 *
 * Usage:
 *   val journal = ReduxMutableSeries(
 *       ChunkedMutableSeries<MyEvent>(),
 *       CollectorReducer<MyEvent>()
 *   )
 *   journal.dispatch(event)
 *   journal.state  // Series<MyEvent> containing all dispatched events
 */
class CollectorReducer<T> : Reducer<T, Series<T>> {
    @Suppress("UNCHECKED_CAST")
    override val zero: Series<T> = (0 j { throw IndexOutOfBoundsException("empty") }) as Series<T>

    override fun combine(acc: Series<T>, element: T): Series<T> {
        val current = acc.toList()
        return (current + element).toSeries()
    }
}

// ── Constructor intercept factories ────────────────────────────────────────
//
// These factory functions serve as the actual runtime intercept point.
// The ToSeriesMacro source rewriter targets constructor calls and replaces
// them with these factories, but they can also be called directly.

/**
 * Create a [ReduxMutableSeries] that journals all additions through
 * a [CollectorReducer], backed by a [ChunkedMutableSeries].
 *
 * This is the primary constructor intercept: anywhere code previously
 * wrote `mutableListOf<T>()` or `ArrayList<T>()`, this factory provides
 * a drop-in replacement that preserves the full emission history.
 *
 * Usage:
 *   // replaces: val items = mutableListOf<Event>()
 *   val items = reduxSeriesOf<Event>()
 *
 *   items.dispatch(event)        // journal the action
 *   items.state                  // lazily reified Series<Event>
 *   items.eventJournal.toList()  // raw event log
 */
fun <T> reduxSeriesOf(chunkSize: Int = 4096, capture: T? = null): ReduxMutableSeries<T, Series<T>> =
    ReduxMutableSeries(
        eventJournal = ChunkedMutableSeries<T>(chunkSize),
        reducer = CollectorReducer<T>(),
        capture = capture as? T ?: throw IllegalArgumentException("capture required for domain schema"),
    )

/**
 * Wrap an existing [MutableSeries] in a Redux journal with a
 * [CollectorReducer].  The delegate forwards all [MutableSeries]
 * operations to [delegate] while journaling dispatches separately.
 *
 * Usage:
 *   val backing = ChunkedMutableSeries<String>()
 *   val journaled = reduxSeriesFrom(backing)
 */
fun <T> reduxSeriesFrom(delegate: MutableSeries<T>, capture: T? = null): ReduxMutableSeries<T, Series<T>> =
    ReduxMutableSeries(
        eventJournal = delegate,
        reducer = CollectorReducer<T>(),
        capture = capture as? T ?: throw IllegalArgumentException("capture required for domain schema"),
    )