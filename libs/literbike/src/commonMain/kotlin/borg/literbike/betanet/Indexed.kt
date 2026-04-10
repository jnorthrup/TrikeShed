package borg.literbike.betanet

/**
 * Indexed<T> = Join<Int, Int->T> - TrikeShed's core abstraction for zero-allocation sequences.
 * Ported from literbike/src/betanet/indexed.rs and baby_pandas.rs (Join, Indexed types).
 */

/**
 * Core Join pattern - pure categorical atom.
 * Port of Join<A, B> from baby_pandas.rs.
 */
data class Join<A, B>(
    val first: A,
    val second: B
) {
    companion object {
        fun <A, B> new(first: A, second: B): Join<A, B> = Join(first, second)
    }
}

/**
 * TrikeShed Indexed type - Join<Int, Int->T>
 */
typealias Indexed<T> = Join<Int, (Int) -> T>

/**
 * Indexed operations for categorical composition.
 */
object IndexedOps {
    /** Map function over indexed sequence (lazy) */
    fun <T, U> map(indexed: Indexed<T>, func: (T) -> U): Indexed<U> {
        return Join(
            indexed.first,
            { index -> func(indexed.second(index)) }
        )
    }

    /** Filter indexed sequence (lazy) */
    fun <T> filter(indexed: Indexed<T>, predicate: (T) -> Boolean): Indexed<T?> {
        return Join(
            indexed.first,
            { index ->
                val value = indexed.second(index)
                if (predicate(value)) value else null
            }
        )
    }

    /** Take first n elements */
    fun <T> take(indexed: Indexed<T>, n: Int): Indexed<T> {
        val takeCount = minOf(n, indexed.first)
        return Join(takeCount, indexed.second)
    }

    /** Skip first n elements */
    fun <T> skip(indexed: Indexed<T>, n: Int): Indexed<T> {
        val remaining = (indexed.first - n).coerceAtLeast(0)
        return Join(
            remaining,
            { index -> indexed.second(index + n) }
        )
    }

    /** Fold over indexed sequence */
    fun <T, Acc> fold(indexed: Indexed<T>, init: Acc, func: (Acc, T) -> Acc): Acc {
        var accumulator = init
        for (i in 0 until indexed.first) {
            accumulator = func(accumulator, indexed.second(i))
        }
        return accumulator
    }

    /** Create indexed from function */
    fun <T> fromFn(size: Int, func: (Int) -> T): Indexed<T> {
        return Join(size, func)
    }

    /** Zip two indexed sequences */
    fun <T, U> zip(left: Indexed<T>, right: Indexed<U>): Indexed<Pair<T, U>> {
        val minLen = minOf(left.first, right.first)
        return Join(
            minLen,
            { index -> left.second(index) to right.second(index) }
        )
    }

    /** Chain two indexed sequences */
    fun <T> chain(first: Indexed<T>, second: Indexed<T>): Indexed<T> {
        val totalLen = first.first + second.first
        val firstLen = first.first
        return Join(
            totalLen,
            { index ->
                if (index < firstLen) first.second(index)
                else second.second(index - firstLen)
            }
        )
    }
}

/**
 * Extension functions for Indexed sequences.
 */
fun <T : Any> Indexed<T>.collect(): List<T> {
    val result = mutableListOf<T>()
    for (i in 0 until first) {
        result.add(second(i))
    }
    return result
}

fun <T> Indexed<T>.get(index: Int): T? {
    return if (index < first) second(index) else null
}

fun <T, U> Indexed<T>.mapIndexed(func: (T) -> U): Indexed<U> {
    return IndexedOps.map(this, func)
}
