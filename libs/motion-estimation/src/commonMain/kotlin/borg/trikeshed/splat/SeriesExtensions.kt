package borg.trikeshed.splat

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.math.exp
import kotlin.math.log

/** Extensions for TrikeShed Series algebra compatibility */
fun <T> List<T>.toSeries(): Series<T> = size.j { this[it] }
fun <T> MutableList<T>.toSeries(): Series<T> = size.j { this[it] }

inline fun <T, R> Series<T>.mapIndexed(crossinline transform: (Int, T) -> R): Series<R> =
    size.j { i -> transform(i, this[i]) }

inline fun <T> Series<T>.withIndex(): Series<Int> = size.j { it }

inline fun <T> MutableList<T>.mapIndexed(crossinline transform: (Int, T) -> T): MutableList<T> =
    this.mapIndexed { i, v -> transform(i, v) }.toMutableList()

fun <T> Series<T>.sum(): Double =
    view.sum()

/** Element-wise max */
fun maxOf(a: Double, b: Double): Double = if (a > b) a else b