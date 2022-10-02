package borg.trikeshed.lib

typealias Series<A> = Join<Int, (Int) -> A>

val <A> Series<A>.size: Int get() = a

//get operators
operator fun <A> Series<A>.get(i: Int): A = b(i)


