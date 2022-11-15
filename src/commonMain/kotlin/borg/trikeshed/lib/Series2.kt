package borg.trikeshed.lib

typealias Series2<A, B> = Series<Join<A, B>>

val <A,B> Series2<A,B>.left:Series<A> get() = this α Join<A, B>::a
val <A,B> Series2<A,B>.right:Series<B> get() = this α Join<A, B>::b