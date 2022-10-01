package borg.trikeshed.lib

typealias Series<A> = Join<Int, (Int) -> A>

val <A> Series<A>.size: Int get() = a

