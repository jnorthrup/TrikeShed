package borg.trikeshed.lib


/**
 * a Monad is a container that can be:
 *  - converted to a list
 *  - chunked
 *  - distinct
 *  - distinctBy
 *  - distinctUntilChanged
 *  - distinctUntilChangedBy
 *  - filtered
 *  - flatmapped over
 *  - folded
 *  - grouped
 *  - joined
 *  - mapped over
 *  - partitioned
 *  - reduced
 *  - reversed
 *  - scanned
 *  - shuffled
 *  - sorted
 *  - zipped
 *
 */
typealias Monad<A> = Join<Series<A>, (A) -> Series<A>>

val <A> Monad<A>.series get() = a
val <A> Monad<A>.size get() = series.size
val <A> Monad<A>.list get() = (0 until size).map { series.b(it) }
val <A> Monad<A>.sequence get() = sequence<A> { for (i in 0 until size) yield(series.b(i)) }
val <A> Monad<A>.iterator get() = list.iterator()
val <A> Monad<A>.asSequence get() = sequence.iterator()
val <A> Monad<A>.asIterable get() = list

