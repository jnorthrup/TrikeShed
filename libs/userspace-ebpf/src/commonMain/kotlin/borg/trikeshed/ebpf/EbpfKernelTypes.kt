package borg.trikeshed.ebpf

/**
 * Minimal kernel algebra types for eBPF integration.
 * Mirrors the types defined in PRELOAD.md and TrikeShed kernel.
 */

/**
 * Base binary composition type.
 */
interface Join<out A, out B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b
    val pair: Pair<A, B> get() = Pair(a, b)
}

data class JoinImpl<A, B>(override val a: A, override val b: B) : Join<A, B>

/** exacly like `to` for Join, but shorter and idiomatic to the algebra */
infix fun <A, B> A.j(b: B): Join<A, B> = JoinImpl(this, b)

typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>

val <T> Series<T>.size: Int get() = a
operator fun <T> Series<T>.get(i: Int): T = b(i)

/** lazy projection over a Series */
inline infix fun <X, C, V : Series<X>> V.α(crossinline xform: (X) -> C): Series<C> =
    size j { i -> xform(this[i]) }

/** view as iterable for .map, .filter, etc. */
val <T> Series<T>.view: Iterable<T> get() = IterableSeries(this)

class IterableSeries<T>(private val series: Series<T>) : Iterable<T> {
    override fun iterator() = object : Iterator<T> {
        private var index = 0
        override fun hasNext(): Boolean = index < series.size
        override fun next(): T = series[index++]
    }
}

/** Extension for filtering Series via view */
fun <T> Iterable<T>.toSeries(): Series<T> {
    val list = toList()
    return list.size j { i -> list[i] }
}