package borg.trikeshed.lib

interface Join<A, B> {
    val a: A
    val b: B
}

private data class PairJoin<A, B>(override val a: A, override val b: B) : Join<A, B>

infix fun <A, B> A.j(b: B): Join<A, B> = PairJoin(this, b)

typealias Series<T> = Join<Int, (Int) -> T>

val <T> Series<T>.size: Int get() = a

operator fun <T> Series<T>.get(index: Int): T = b(index)
