package borg.trikeshed.lib.doublep

import borg.trikeshed.lib.intp.Comparison
import borg.trikeshed.lib.intp.identitySelection
import kotlin.test.*

class DoubleKernelsTest {
    val v = doubleArrayOf(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0, 6.0)

    @Test fun whereThenRefineMatchesFilter() {
        val sel = IntArray(v.size)
        var m = where(v, v.size, Comparison.GE, 2.0, sel)
        assertEquals(listOf(0, 2, 4, 5, 6, 7), sel.take(m))
        m = refine(v, sel, m, Comparison.LT, 6.0, sel)
        assertEquals(listOf(0, 2, 4, 6), sel.take(m))
        assertEquals(14.0, sum(v, sel, m))
        assertEquals(2.0, min(v, sel, m))
        assertEquals(5.0, max(v, sel, m))
    }

    @Test fun nanFailsEveryComparisonButNe() {
        val x = doubleArrayOf(Double.NaN, 1.0)
        val sel = IntArray(2)
        for (op in Comparison.entries) {
            val m = where(x, 2, op, 1.0, sel)
            val expected = when (op) {
                Comparison.EQ, Comparison.LE, Comparison.GE -> listOf(1)
                Comparison.NE -> listOf(0)
                else -> emptyList()
            }
            assertEquals(expected, sel.take(m), "$op")
        }
    }

    @Test fun arithmeticOverSelection() {
        val b = doubleArrayOf(2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0)
        val sel = intArrayOf(0, 2, 5)
        val out = DoubleArray(3)
        mul(v, b, sel, 3, out)
        assertEquals(listOf(6.0, 8.0, 18.0), out.toList())
        affine(v, -1.0, 10.0, sel, 3, out)
        assertEquals(listOf(7.0, 6.0, 1.0), out.toList())
    }

    @Test fun groupSums() {
        val ids = intArrayOf(0, 1, 0, 1)
        val sums = DoubleArray(2)
        sumByGroup(doubleArrayOf(1.0, 2.0, 3.0, 4.0), identitySelection(4), ids, 4, sums)
        assertEquals(listOf(4.0, 6.0), sums.toList())
        val mins = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        minByGroup(doubleArrayOf(5.0, 2.0, 3.0, 4.0), identitySelection(4), ids, 4, mins)
        assertEquals(listOf(3.0, 2.0), mins.toList())
    }

    @Test fun topKOrdersBestFirstTiesByOrdinal() {
        assertEquals(listOf(5, 7, 4), topK(v, v.size, 3, descending = true).toList())
        assertEquals(listOf(1, 3, 6), topK(v, v.size, 3, descending = false).toList())
        assertEquals(v.size, topK(v, v.size, 100, descending = true).size)
        assertEquals(0, topK(v, v.size, 0, descending = true).size)
        val tied = doubleArrayOf(1.0, 1.0, 1.0, 0.0)
        assertEquals(listOf(0, 1), topK(tied, 4, 2, descending = true).toList())
    }
}
