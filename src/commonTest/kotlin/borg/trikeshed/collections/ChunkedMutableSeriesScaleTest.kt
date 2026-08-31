package borg.trikeshed.collections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The regression for the hang that made `./gradlew jvmTest` unrunnable.
 *
 * Every mutation used to compose a closure over the previous state, so N appends
 * built an N-deep chain that every read walked: O(N) per get, O(N²) to fill, and
 * unbounded stack growth. A thread dump of the stuck suite was 1081 frames of
 * `JoinKt.get` ⇄ `ChunkedMutableSeries.append$lambda$2` with 81 minutes of CPU
 * burned inside the Confix parser benchmark.
 *
 * These cases are ordinary usage — fill it, read it back, snapshot it. They are
 * regressions only because the old representation could not survive them at
 * size. No timing assertion: the proof is that they COMPLETE, which the closure
 * chain could not do.
 */
class ChunkedMutableSeriesScaleTest {

    /** Well past one chunk (4096), so the chunk table is exercised too. */
    private val N = 50_000

    @Test
    fun fillsAndReadsBackAtScale() {
        val s = ChunkedMutableSeries<Int>()
        for (i in 0 until N) s.append(i)
        assertEquals(N, s.a)

        // Forward, backward, and random-ish probes: the old version degraded on
        // every one of these, and the reverse walk was the worst case.
        for (i in 0 until N) assertEquals(i, s.b(i))
        for (i in N - 1 downTo 0) assertEquals(i, s.b(i))
        var probe = 1
        while (probe < N) { assertEquals(probe, s.b(probe)); probe = probe * 3 + 1 }
    }

    @Test
    fun readsAreCorrectAcrossChunkBoundaries() {
        // A tiny chunk size makes the boundary arithmetic dense: 1000 elements
        // over chunks of 7 is 143 chunks, so most reads cross one.
        val s = ChunkedMutableSeries<Int>(chunkSize = 7)
        for (i in 0 until 1000) s.append(i)
        assertEquals(1000, s.a)
        for (i in 0 until 1000) assertEquals(i, s.b(i))
        assertEquals(999, s.b(999))
        assertEquals(0, s.b(0))
    }

    @Test
    fun setInsertAndRemoveKeepTheSeriesConsistent() {
        val s = ChunkedMutableSeries<Int>(chunkSize = 4)
        for (i in 0 until 20) s.append(i)

        s.set(0, -1); s.set(19, -19)
        assertEquals(-1, s.b(0)); assertEquals(-19, s.b(19)); assertEquals(20, s.a)

        s.insert(5, 999)
        assertEquals(21, s.a)
        assertEquals(999, s.b(5))
        assertEquals(5, s.b(6), "insert must shift, not overwrite")

        assertEquals(999, s.removeAt(5))
        assertEquals(20, s.a)
        assertEquals(5, s.b(5))

        assertTrue(s.remove(7)); assertEquals(19, s.a)
        assertFalse(s.remove(4242))

        s.clear(); assertEquals(0, s.a)
    }

    @Test
    fun aSnapshotDoesNotAliasTheOriginal() {
        // The old snapshot shared the chunk series outright, so appends to the
        // original showed up through the "snapshot".
        val s = ChunkedMutableSeries<Int>(chunkSize = 8)
        for (i in 0 until 10) s.append(i)
        val snap = s.snapshot()
        assertEquals(10, snap.a)

        for (i in 10 until 20) s.append(i)
        s.set(0, -100)

        assertEquals(10, snap.a, "the snapshot must not grow with the original")
        assertEquals(0, snap.b(0), "the snapshot must not see later writes")
        assertEquals(20, s.a)
        assertEquals(-100, s.b(0))
    }

    @Test
    fun freezeAndIterationAgreeWithIndexedReads() {
        val s = ChunkedMutableSeries<Int>(chunkSize = 16)
        for (i in 0 until 100) s.append(i * 2)
        val frozen = s.freeze()
        assertEquals(100, frozen.a)
        for (i in 0 until 100) assertEquals(s.b(i), frozen.b(i))
        assertEquals((0 until 100).map { it * 2 }, s.sequence().toList())
    }
}
