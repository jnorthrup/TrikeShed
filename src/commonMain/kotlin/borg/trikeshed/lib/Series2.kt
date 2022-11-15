package borg.trikeshed.lib

typealias Series2<A, B> = Series<Join<A, B>>

val <A> Series2<A, *>.left:Series<A> get() = this α Join<A, *>::a
val <B> Series2<*, B>.right:Series<B> get() = this α Join<*, B>::b