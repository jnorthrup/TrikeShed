package borg.trikeshed.collections.bits

import borg.trikeshed.lib.get
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The three containers agree with a reference Set, and the chooser picks the shape the data has. */
class RoaringSeriesTest {

    private fun check(values: Set<Int>, s: RoaringSeries) {
        assertEquals(values.size, s.cardinality)
        assertEquals(values.sorted(), s.toIntArray().toList())
        for (v in values) assertTrue(s.contains(v), "contains $v")
        for (v in listOf(-1, 0, 1, 65535, 65536, 65537, 1 shl 20)) assertEquals(v in values, s.contains(v), "contains $v")
        val sorted = values.sorted()
        for (k in sorted.indices step maxOf(1, sorted.size / 50)) assertEquals(sorted[k], s.members[k], "members[$k]")
    }

    @Test
    fun chooserPicksRunsForContiguousArraysForSparseBitmapsForDense() {
        val runs = RoaringSeries.range(10, 3000) or RoaringSeries.range(5000, 5010)
        assertEquals(mapOf("array" to 0, "run" to 1, "bitmap" to 0), runs.shapeHistogram())
        check((10 until 3000).toSet() + (5000 until 5010), runs)

        val sparse = RoaringSeries.of(intArrayOf(3, 17, 900, 65535, 65536, 70000))
        assertEquals(mapOf("array" to 2, "run" to 0, "bitmap" to 0), sparse.shapeHistogram())
        check(setOf(3, 17, 900, 65535, 65536, 70000), sparse)

        val rnd = Random(7)
        val dense = HashSet<Int>()
        while (dense.size < 20000) dense += rnd.nextInt(65536)
        val bitmap = RoaringSeries.of(dense)
        assertEquals(mapOf("array" to 0, "run" to 0, "bitmap" to 1), bitmap.shapeHistogram())
        check(dense, bitmap)
    }

    @Test
    fun setAlgebraMatchesTheReferenceAcrossShapesAndChunks() {
        val rnd = Random(11)
        val a = HashSet<Int>(); val b = HashSet<Int>()
        repeat(6000) { a += rnd.nextInt(70000) }
        (100 until 2500).forEach { b += it }
        repeat(300) { b += rnd.nextInt(200000) }
        val ra = RoaringSeries.of(a); val rb = RoaringSeries.of(b)
        check(a, ra); check(b, rb)
        check(a union b, ra or rb)
        check(a intersect b, ra and rb)
        check(a subtract b, ra andNot rb)
        check(b subtract a, rb andNot ra)
        assertTrue(ra.intersects(rb)); assertFalse(ra.intersects(RoaringSeries.EMPTY))
        assertEquals(ra, RoaringSeries.of(a.toIntArray().reversedArray() + a.toIntArray()), "order and duplicates do not matter")
        assertTrue((ra andNot ra).isEmpty())
    }
}
