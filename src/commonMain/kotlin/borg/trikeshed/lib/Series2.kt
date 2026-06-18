package borg.trikeshed.lib

// Series2 and left/right projections defined in Join.kt

//left join
operator fun <A, B> Series<Series2<A, B>>.unaryMinus(): Series<Series<A>> =
    this α Series2<A, B>::left

//right join
operator fun <A, B> Series<Series2<A, B>>.unaryPlus(): Series<Series<B>> = this α Series2<A, B>::right
