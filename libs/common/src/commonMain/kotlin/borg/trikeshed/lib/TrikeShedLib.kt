package borg.trikeshed.lib

interface Join<A, B> {
    val a: A
    val b: B
}

private data class JoinImpl<A, B>(
    override val a: A,
    override val b: B,
) : Join<A, B>

infix fun <A, B> A.j(b: B): Join<A, B> = JoinImpl(this, b)

typealias MetaSeries<I, T> = Join<I, (I) -> T>
typealias Series<T> = MetaSeries<Int, T>

val <T> Series<T>.size: Int
    get() = a

operator fun <T> Series<T>.get(index: Int): T = b(index)

fun <T> List<T>.toSeries(): Series<T> = size j { index: Int -> this[index] }
fun <T> Array<T>.toSeries(): Series<T> = size j { index: Int -> this[index] }
fun DoubleArray.toSeries(): Series<Double> = size j { index: Int -> this[index] }
fun IntArray.toSeries(): Series<Int> = size j { index: Int -> this[index] }
fun String.toSeries(): Series<Char> = length j { index: Int -> this[index] }

fun <T> Series<T>.toList(): List<T> = List(size) { index -> b(index) }
