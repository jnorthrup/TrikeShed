package borg.trikeshed.lib

typealias MetaSeries<I, T> = Join<I, (I) -> T>

/* simplest assoc functions */

/** primary bounds function  */
val <I,T> MetaSeries<I,T>.size: I get() = a

/** index operator for Series */
operator fun <L,T> MetaSeries<L,T>.get(i: L): T = b(i)
