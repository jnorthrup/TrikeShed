package borg.trikeshed.lib


typealias Predicate<T> = (self: T) -> Boolean

//test operator
operator fun <T> T.get(test: Predicate<T>): Boolean = test(this)

//filter iterator
operator fun <T> Series<T>.get(test: Predicate<T>): Iterator<T> =
    iterator { for (i in 0 until size) if (test(get(i))) yield(get(i)) }

//filter for indices
operator fun <T> Series<T>.rem(test: Predicate<T>): Iterator<Int> =
    iterator { for (i in 0 until size) if (test(get(i))) yield(i) }

/**
 * @param pred:Predicate<T>
 * @return Iterable<T>
 *
creates a filtered Iterable from an Iterable which pumps this.next inside of child hasNext() in a loop until predicate is satisfied
 */
infix fun <T> Iterable<T>.where(pred: Predicate<T>) = let { source ->
    object : Iterable<T> {

        override fun iterator(): Iterator<T> {
            var theNext: T? = null

            return object : Iterator<T> {
                val sourceIter = source.iterator()
                override fun hasNext(): Boolean {
                    while (true) { //loop until predicate is satisfied
                        if (theNext != null) return true
                        if (!sourceIter.hasNext()) return false
                        val testNext = sourceIter.next()
                        if (pred(testNext)) {
                            theNext = testNext
                            return true
                        }
                    }
                }

                override fun next(): T {

                    if (theNext == null) {
                        hasNext()
                    }
                    val theNext2 = theNext
                    theNext = null
                    return theNext2 ?: throw NoSuchElementException()
                }
            }
        }
    }

}

//same as where above but transforming the result instead of testing it
infix fun <T, R> Iterable<T>.select(from: (T) -> R) = let { source ->
    object : Iterable<R> {

        override fun iterator(): Iterator<R> {
            var theNext: R? = null

            return object : Iterator<R> {
                val sourceIter = source.iterator()
                override fun hasNext(): Boolean {
                    while (true) { //loop until predicate is satisfied
                        if (theNext != null) return true
                        if (!sourceIter.hasNext()) return false
                        val xformNext = sourceIter.next()
                        theNext = from(xformNext)
                        return true
                    }
                }

                override fun next(): R {

                    if (theNext == null) {
                        hasNext()
                    }
                    val theNext2 = theNext
                    theNext = null
                    return theNext2 ?: throw NoSuchElementException()
                }
            }
        }
    }
}