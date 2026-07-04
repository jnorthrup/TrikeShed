package borg.trikeshed.mutable

import borg.trikeshed.cursor.Evidence

/**
 * Represents a discrete mutation on a MutableSeries.
 * These actions are captured by a PointcutMutableSeries and dispatched
 * to an event sink (like a ReduxMutableSeries) to provide an audit log/firehose.
 */
sealed class MutationAction<T> {
    abstract val evidence: Evidence? // Optional confidence/errorMargin metadata

    data class Set<T>(
        val index: Int, 
        val item: T,
        override val evidence: Evidence? = null
    ) : MutationAction<T>()

    data class Add<T>(
        val item: T,
        override val evidence: Evidence? = null
    ) : MutationAction<T>()

    data class AddAtIndex<T>(
        val index: Int, 
        val item: T,
        override val evidence: Evidence? = null
    ) : MutationAction<T>()

    data class RemoveAt<T>(
        val index: Int,
        override val evidence: Evidence? = null
    ) : MutationAction<T>()

    data class Remove<T>(
        val item: T,
        override val evidence: Evidence? = null
    ) : MutationAction<T>()

    data class Clear<T>(
        override val evidence: Evidence? = null
    ) : MutationAction<T>()

    // For operator overloads
    data class Plus<T>(
        val item: T,
        override val evidence: Evidence? = null
    ) : MutationAction<T>()

    data class Minus<T>(
        val item: T,
        override val evidence: Evidence? = null
    ) : MutationAction<T>()
}