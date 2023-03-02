package borg.trikeshed.lib


typealias Predicate<T> = (self: T) -> Boolean

//test operator
operator fun <T> T.get(test: Predicate<T>) = test(this)

//filter iterator
operator fun <T> Series<T>.get(test: Predicate<T>): Iterator<T> = iterator { for (i in 0 until size) if (test(get(i))) yield(get(i)) }

//filter for indices
operator fun <T> Series<T>.rem(test:  Predicate<T> ) = iterator { for (i in 0 until size) if (test(get(i))) yield(i) }