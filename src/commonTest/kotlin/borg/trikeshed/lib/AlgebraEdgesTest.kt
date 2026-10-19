package borg.trikeshed.lib

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.metas
import borg.trikeshed.cursor.values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import borg.trikeshed.cursor.ReifiedSplitSeries2 as CursorSplit

/** The algebra's monomorphic edges keep the laws of the forms they replace: laziness, lengths, reversal, packing. */
class AlgebraEdgesTest {
    @Test
    fun seriesConstructionIsLazyOneClassAndReversesAsBefore() {
        var calls = 0
        val s = 5 j { i: Int -> calls++; i * 2 }
        assertEquals(0, calls, "construction never calls the oracle")
        // Checked into locals: assertTrue's contract would smart-cast s itself to OracleSeries<*, *>.
        val oracle = s is Joined<*, *>
        val oneClass = s::class == (3 j { i: Int -> "x$i" })::class
        assertTrue(oracle)
        assertTrue(oneClass, "every size j { } is one class")
        assertTrue((7 j "seven") is Joined<*, *>, "so is every pair that does not pack")
        assertEquals(5, s.size)
        assertEquals(8, s[4])
        assertEquals(1, calls)
        val r = s.reversed()
        assertEquals(5, r.size)
        for (i in 0 until 5) assertEquals(8 - 2 * i, r[i], "reversal reads index ${4 - i}")
        assertEquals(listOf("a", "b"), listOf("a", "b").toSeries().toList())
    }

    private fun <A, B> erased(a: A, b: B): Join<A, B> = a j b

    @Test
    fun primitivePairsStillPackThroughErasedConstruction() {
        assertTrue(erased(1, 'c') is IntCharJoin, "an erased Int/Char pair still packs")
        assertTrue(erased(true, 7) is BooleanIntJoin)
        val p = erased(3, "x")
        assertTrue(p is Joined<*, *>)
        assertEquals(3, p.a)
        assertEquals("x", p.b)
    }

    @Test
    fun byteArraySeriesIsALiveViewWhoseArrayIsOneTypeTestAway() {
        val bytes = byteArrayOf(1, 2, 3)
        val s = bytes.toSeries()
        val named = s as? ByteArraySeries
        assertSame(bytes, named?.bytes)
        bytes[1] = 9
        assertEquals(9.toByte(), s[1], "writes to the array show through, as with size j ::get")
        assertEquals(3, s.size)
    }

    @Test
    fun splitProjectionsKeepTheirLengthWhenSidesDiffer() {
        val left3 = listOf("L0", "L1", "L2").toSeries()
        val right2 = listOf(0, 10).toSeries()
        val lib = ReifiedSplitSeries2(left3, right2)
        assertEquals(2, lib.size)
        assertEquals(listOf("L0", "L1"), lib.left.toList(), "lib: the shorter side's length, as before")
        assertEquals(listOf(0, 10), lib.right.toList())
        val even = ReifiedSplitSeries2(left3, listOf(0, 1, 2).toSeries())
        assertEquals(listOf("L0", "L1", "L2"), even.left.toList())
        assertEquals(listOf(0, 1, 2), even.right.toList())
        // The cursor split's length is its left side's: its metas keep that length and fail on the missing right
        // index, as the α view always did, instead of exposing the right side's own length.
        @Suppress("UNCHECKED_CAST")
        val row = CursorSplit(left3, right2) as RowVec
        assertEquals(3, row.size)
        assertEquals(3, row.metas.size)
        assertFailsWith<IndexOutOfBoundsException> { row.metas[2] }
        assertEquals(3, row.values.size)
        @Suppress("UNCHECKED_CAST")
        val evenRow = CursorSplit(left3, listOf(0, 1, 2).toSeries()) as RowVec
        assertSame((evenRow as CursorSplit<*, *>).rightSeries, evenRow.metas, "equal sides: the side itself")
    }

    @Test
    fun splitProjectionsReadTheSelectedSideLive() {
        var fn: (Int) -> String = { "old" }
        val side = object : Series<String> {
            override val a: Int = 1
            override val b: (Int) -> String get() = fn
        }
        var opposite = 0
        val other = object : Series<Int> {
            override val a: Int = 1
            override val b: (Int) -> Int get() = { opposite++; 0 }
        }
        val projected = ReifiedSplitSeries2(side, other).left
        fn = { "new" }
        assertEquals("new", projected[0], "a replaced oracle shows through the projection, as this[it].a showed it")
        assertEquals(1, projected.size)
        assertEquals(0, opposite, "the equal-size left projection never evaluates the right side")
        var rfn: (Int) -> Int = { 1 }
        val live = object : Series<Int> {
            override val a: Int = 1
            override val b: (Int) -> Int get() = rfn
        }
        val rightProjected = ReifiedSplitSeries2(side, live).right
        rfn = { 2 }
        assertEquals(2, rightProjected[0])
        assertEquals(2, rightProjected.reversed()[0])
    }

    @Test
    fun twinSplitKeepsItsReversal() {
        val twins: Series<Twin<Int>> = 3 j { i: Int -> Twin(i, -i) }
        val split = ReifiedSplitSeries2.invoke<Int>(twins)
        assertEquals(-1, split.leftSeries[1], "the left side holds the twins' b values, as it always has")
        assertEquals(1, split.rightSeries[1])
        assertEquals(listOf(0, -1, -2), split.left.toList())
    }
}
