package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Series with long Indexes for large files
 */
typealias LongSeries<T> = Join<Long, (Long) -> T>


val <T> LongSeries<T>.size: Long get() = a

/**
 * index operator for borg.trikeshed.common.LongSeries
 */
operator fun <T> LongSeries<T>.get(i: Long): T = b(i)


/**int series is returned for intRange.  Should we also return LongSeries for LongRange?*/
operator fun <T> LongSeries<T>.get(r: IntRange): Series<T> {
    //perform fixup between the range and the Series x index
    return (r.last - r.first) j { x  -> this[(r.first + x).toLong()] }
}

operator fun <T> LongSeries<T>.get(r: LongRange): LongSeries<T> {
    //perform fixup between the range and the Series x index
    return (r.last - r.first) j { x -> this[(r.first + x)] }
}

fun <T> LongSeries<T> . slice( start: Long, end: Long =size): LongSeries<T> = (end - start).toLong() j { x -> this[start + x] }

fun <T> LongSeries<T> . drop(  removeInitial: Long)=slice(removeInitial)
