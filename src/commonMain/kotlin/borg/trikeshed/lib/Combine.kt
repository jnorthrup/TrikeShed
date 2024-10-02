package borg.trikeshed.lib

import borg.trikeshed.common.collections.s_

operator fun <A> Series<A>.plus(c: Series<A>): Series<A> = combine(s_[this, c])

/**
Series combine (series...)
creates a new Series<A> from the varargs of Series<A> passed in
which is a view of the underlying data, not a copy

the resulting Series<A> is ordered and contains all the elements of catn
in the order they were passed in
@see https://en.algorithmica.org/hpc/data-structures/s-tree/#b-tree-layout-1
@param catn the varargs of Series<A> to combine
 */
fun <A> combine(vararg catn: Series<A>): Series<A> = combine((catn).size j catn::get)

/**
Series combine (series...)
creates a new Series<A> from the varargs of Series<A> passed in
which is a view of the underlying data, not a copy

the resulting Series<A> is ordered and contains all the elements of catn
in the order they were passed in
@see https://en.algorithmica.org/hpc/data-structures/s-tree/#b-tree-layout-1
@param catn the Series of Series<A> to combine
 */
fun <A> combine(catn: Series<Series<A>>): Series<A> {
    return when (val szN = catn.size) {
        0 -> EmptySeries as Series<A>
        1 -> catn[0]

        else -> {
            val stairs by lazy {
                val coords: Series<Int> = catn α Join<Int, (Int) -> A>::a
                var acc = 0
                IntArray(szN) { acc += it; acc }
            }
            val sumSize = stairs[szN - 1]


            sumSize j { i: Int ->

              val idx=  when (szN) {

                    in 2..4 -> stairs.indexOfFirst { it > i }
                    else -> stairs.binarySearch(i)
                }
                val series = catn[idx]
                val offset = if (idx == 0) i else i - stairs[idx - 1]
                series[offset]
            }
        }
    }
}





