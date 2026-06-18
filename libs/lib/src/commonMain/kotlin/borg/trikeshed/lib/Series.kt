package borg.trikeshed.lib

import kotlin.jvm.JvmName

// --- Series type: simple List ---
typealias Series<T> = List<T>

typealias Join<A, B> = Pair<A, B>
infix fun <A, B> A.j(b: B): Join<A, B> = this to b

// --- toSeries: various -> Series ---
@JvmName("listToSeries")
fun <T> List<T>.toSeries(): Series<T> = this

@JvmName("mutableListToSeries")
fun <T> MutableList<T>.toSeries(): Series<T> = this

@JvmName("arrayToSeries")
fun <T> Array<T>.toSeries(): Series<T> = this.toList()

@JvmName("iterableToSeries")
fun <T> Iterable<T>.toSeries(): Series<T> = this.toList()

// --- Alpha projection ---
inline infix fun <X, C> Series<X>.α(crossinline xform: (X) -> C): Series<C> =
    this.map(xform)

inline infix fun <X, C> List<X>.α(crossinline xform: (X) -> C): List<C> =
    this.map(xform)

// --- View extension ---
class IterableSeries<T>(private val series: Series<T>) : Iterable<T> by series
val <T> Series<T>.view: Iterable<T> get() = IterableSeries(this)
