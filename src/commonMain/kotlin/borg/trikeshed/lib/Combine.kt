package borg.trikeshed.lib

import borg.trikeshed.common.collections.s_

operator fun <A> Series<A>.plus(c: Series<A>): Series<A> = combine(s_[this] as Series<A>, c)

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
fun <A> combine(catn: Series<Series<A>>): Series<A> { // combine

    val frst = catn[0]
    val sz0 = frst.size
    val captureSize = catn.size
    return when (captureSize) {
        0 -> 0 j { x: Int -> TODO() }
        1 -> frst
        2 -> sz0 + catn[1].size j { i ->
            if (i < sz0) frst[i] else catn[1][i - sz0]
        }

        3 -> sz0 + catn[1].size + catn[2].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                else -> catn[2][i - sz0 - catn[1].size]
            }
        }

        4 -> sz0 + catn[1].size + catn[2].size + catn[3].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                else -> catn[3][i - sz0 - catn[1].size - catn[2].size]
            }
        }

        5 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                else -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
            }
        }

        6 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
                else -> catn[5][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size]
            }
        }

        7 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size -> catn[5][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size]
                else -> catn[6][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size]
            }
        }

        8 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size + catn[7].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size -> catn[5][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size -> catn[6][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size]
                else -> catn[7][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size - catn[6].size]
            }
        }

        9 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size + catn[7].size + catn[8].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size -> catn[5][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size -> catn[6][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size + catn[7].size -> catn[7][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size - catn[6].size]
                else -> catn[8][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size - catn[6].size - catn[7].size]
            }
        }

        else -> {
            val offsets = IntArray(captureSize)
            var offset = 0
            for (i in 0 until captureSize) {
                offsets[i] = offset
                offset += catn[i].size
            }
            offset j { i ->
                val j = offsets.binarySearch(i)
                if (j >= 0) catn[j][i - offsets[j]] else catn[-j - 2][i - offsets[-j - 2]]
            }
        }
    }
} // combine