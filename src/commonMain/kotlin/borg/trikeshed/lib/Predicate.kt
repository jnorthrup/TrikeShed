package borg.trikeshed.lib


typealias Predicate<T> = (self: T) -> Boolean

//test operator
operator fun <T> T.get(test: Predicate<T>): Boolean = test(this)

//filter method for Series
fun <T> Series<T>.filter(test: Predicate<T>): Series<T> {
    var indices = IntArray(if (size < 10) size else 10)
    var count = 0
    for (i in 0 until size) {
        if (test(get(i))) {
            if (count == indices.size) {
                indices = indices.copyOf(indices.size * 2)
            }
            indices[count++] = i
        }
    }
    
    
    val finalIndices = if (count == indices.size) indices else indices.copyOf(count)
    return finalIndices.size j { i -> this[finalIndices[i]] }
}

//filter operator
operator fun <T> Series<T>.get(test: Predicate<T>): Series<T> = filter(test)

//filter for indices
operator fun <T> Series<T>.rem(test: Predicate<T>): Series<Int> {
    var matched = IntArray(if (size < 10) size else 10)
    var count = 0
    for (i in 0 until size) {
        if (test(get(i))) {
            if (count == matched.size) {
                matched = matched.copyOf(matched.size * 2)
            }
            matched[count++] = i
        }
    }
    
    
    val indices = if (count == matched.size) matched else matched.copyOf(count)
    return indices.size j { i -> indices[i] }
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

/** creates a filtered Iterable from an Iterable which pumps this.next inside of child hasNext() in a loop until
 * predicate is satisfied
 */
infix fun <T> Iterable<T>.where(pred: Predicate<T>) = let { source ->

    object : Iterable<T> {
        override fun iterator(): Iterator<T> {
            val parentIter = source.iterator()
            var theNext: T? = null

            return object : Iterator<T> {
                override fun hasNext(): Boolean {
                    while (true) { //loop until predicate is satisfied
                        if (theNext != null) return true
                        if (!parentIter.hasNext()) return false
                        val next = parentIter.next()
                        if (pred(next)) {
                            theNext = next
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