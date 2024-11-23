package borg.trikeshed.common

import borg.trikeshed.lib.*

/** Series with long Indexes for large files */
typealias LongSeries<T> = Join<Long, (Long) -> T>

val <V>LongSeries<V>.`▶` get() = require(this.size < Int.MAX_VALUE).run { toSeries().`▶` }

operator fun <T> LongSeries<T>.get(i: Long): T = b(i)

operator fun <T> LongSeries<T>.get(exclusiveRange: IntRange): Series<T> {
    //perform fixup between the range and the Series x index
    return (exclusiveRange.last - exclusiveRange.first) j { x -> this[(exclusiveRange.first + x).toLong()] }
}

operator fun <T> LongSeries<T>.get(r: LongRange): LongSeries<T> {
    //perform fixup between the range and the Series x index
    return (r.last - r.first) j { x -> this[(r.first + x)] }
}

fun <T> LongSeries<T>.slice(start: Long, end: Long = size): LongSeries<T> =
    (end - start) j { x -> this[start + x] }

fun <T> LongSeries<T>.drop(removeInitial: Long): LongSeries<T> = slice(removeInitial)
fun <A> Series<A>.toLongSeries(): LongSeries<A> = a.toLong() j { it: Long -> b(it.toInt()) }

//LongSeries.toSeries
fun <A> LongSeries<A>.toSeries(start: Long = 0L, len: Int = a.toInt().also { require(a < Int.MAX_VALUE) }): Series<A> =
    len j { it: Int -> b(start + it) }


val <T>LongSeries<T>.size get() = a
val <T> LongSeries<T>.isEmpty get() = size == 0L
val <T> LongSeries<T>.isNotEmpty get() = size != 0L



