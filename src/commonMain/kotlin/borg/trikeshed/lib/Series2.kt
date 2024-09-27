package borg.trikeshed.lib

typealias Series2<A, B> = Series<Join<A, B>>

val <T, I> Series2<T, I>.left: Series<T> get() = this.α(Join<T, I>::a)
val <T, I> Series2<I, T>.right: Series<T> get() = this.α(Join<I, T>::b)


//left join
operator fun <A, B> Series<Series2<A, B>>.unaryMinus(): Series<Series<A>> =
    this α Series2<A, B>::left

//right join
operator fun <A, B> Series<Series2<A, B>>.unaryPlus(): Series<Series<B>> = this α Series2<A, B>::right