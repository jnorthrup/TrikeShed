package borg.trikeshed.pointcut

/**
 * Represents a discrete mutation on a MutableSeries.
 * These actions are captured by a JournalSeries and dispatched
 * to an event sink (like a JournalSeries) to provide an audit log/firehose.
 */
sealed class MutationAction<T> {
    data class Set<T>(val index: Int, val item: T) : MutationAction<T>()
    data class Add<T>(val item: T) : MutationAction<T>()
    data class AddAtIndex<T>(val index: Int, val item: T) : MutationAction<T>()
    data class RemoveAt<T>(val index: Int) : MutationAction<T>()
    data class Remove<T>(val item: T) : MutationAction<T>()
    class Clear<T> : MutationAction<T>()
    
    // For operator overloads
    data class Plus<T>(val item: T) : MutationAction<T>()
    data class Minus<T>(val item: T) : MutationAction<T>()
}
