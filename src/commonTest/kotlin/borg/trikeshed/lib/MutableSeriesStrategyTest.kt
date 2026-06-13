@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.lib

import borg.trikeshed.collections.s_
import borg.trikeshed.mutable.ChunkedMutableSeries
import borg.trikeshed.mutable.DequeSeries
import borg.trikeshed.mutable.GuardSeries
import borg.trikeshed.mutable.JournalSeries
import borg.trikeshed.mutable.RingSeries
import borg.trikeshed.mutable.SortedSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFails
import kotlin.test.assertFalse

class MutableSeriesStrategyTest {

    // ═══════════════════════════════════════════════════════════════════
    // RingSeries
    // ═══════════════════════════════════════════════════════════════════

    @Test fun ringSeriesCapacityAndBasicOps() {
        val r = RingSeries<Int>(8)
        assertEquals(0, r.size)

        r.add(10); r.add(20); r.add(30)
        assertEquals(3, r.size)
        assertEquals(10, r[0])
        assertEquals(20, r[1])
        assertEquals(30, r[2])
    }

    @Test fun ringSeriesWrapsOnOverflow() {
        val r = RingSeries<Int>(4)
        for (i in 0 until 6) r.add(i)  // 0,1,2,3 then 4,5 overwrite 0,1
        assertEquals(4, r.size)         // capacity is fixed
        assertEquals(2, r[0])           // oldest surviving: 2
        assertEquals(3, r[1])
        assertEquals(4, r[2])
        assertEquals(5, r[3])
    }

    @Test fun ringSeriesSet() {
        val r = RingSeries<Int>(4)
        for (i in 0 until 4) r.add(i)
        r.set(1, 99)
        assertEquals(99, r[1])
    }

    @Test fun ringSeriesRemoveAt() {
        val r = RingSeries<Int>(4)
        for (i in 0 until 4) r.add(i)  // 0,1,2,3
        val removed = r.removeAt(1)     // remove 1
        assertEquals(1, removed)
        assertEquals(3, r.size)
        assertEquals(0, r[0])
        assertEquals(2, r[1])
        assertEquals(3, r[2])
    }

    @Test fun ringSeriesClear() {
        val r = RingSeries<Int>(8)
        for (i in 0 until 5) r.add(i)
        r.clear()
        assertEquals(0, r.size)
    }

    @Test fun ringSeriesRemove() {
        val r = RingSeries<Int>(4)
        r.add(10); r.add(20); r.add(30)
        assertTrue(r.remove(20))
        assertEquals(2, r.size)
        assertEquals(10, r[0])
        assertEquals(30, r[1])
        assertFalse(r.remove(99))
    }

    @Test fun ringSeriesRejectsNonPowerOfTwo() {
        assertFails { RingSeries<Int>(3) }
        assertFails { RingSeries<Int>(0) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ColumnarSeries — DoubleSeries, LongSeries, IntSeries, FloatSeries
    // ═══════════════════════════════════════════════════════════════════

    @Test fun doubleSeriesConstructAndAccess() {
        val ds = DoubleSeries(4)
        ds.set(0, 1.5); ds.set(1, 2.5); ds.set(2, 3.5); ds.set(3, 4.5)
        assertEquals(4, ds.size)
        assertEquals(1.5, ds[0])
        assertEquals(4.5, ds[3])
    }

    @Test fun doubleSeriesAdd() {
        val ds = DoubleSeries(2)
        ds.set(0, 1.0); ds.set(1, 2.0)
        ds.add(3.0)
        assertEquals(3, ds.size)
        assertEquals(1.0, ds[0])
        assertEquals(2.0, ds[1])
        assertEquals(3.0, ds[2])
    }

    @Test fun doubleSeriesInsert() {
        val ds = DoubleSeries(3)
        ds.set(0, 1.0); ds.set(1, 3.0); ds.set(2, 4.0)
        ds.add(1, 2.0)  // insert 2.0 at index 1
        assertEquals(4, ds.size)
        assertEquals(1.0, ds[0])
        assertEquals(2.0, ds[1])
        assertEquals(3.0, ds[2])
        assertEquals(4.0, ds[3])
    }

    @Test fun doubleSeriesRemoveAt() {
        val ds = DoubleSeries(4)
        ds.set(0, 1.0); ds.set(1, 2.0); ds.set(2, 3.0); ds.set(3, 4.0)
        val r = ds.removeAt(1)
        assertEquals(2.0, r)
        assertEquals(3, ds.size)
        assertEquals(1.0, ds[0])
        assertEquals(3.0, ds[1])
        assertEquals(4.0, ds[2])
    }

    @Test fun doubleSeriesClear() {
        val ds = DoubleSeries(4)
        ds.set(0, 1.0)
        ds.clear()
        assertEquals(0, ds.size)
    }

    @Test fun doubleSeriesPlusAssign() {
        val ds = DoubleSeries(1)
        ds.set(0, 1.0)
        ds += 2.0
        assertEquals(2, ds.size)
        assertEquals(2.0, ds[1])
    }

    @Test fun longSeriesBasic() {
        val ls = LongBackingSeries(3)
        ls.set(0, 100L); ls.set(1, 200L); ls.set(2, 300L)
        assertEquals(3, ls.size)
        assertEquals(200L, ls[1])

        ls.add(400L)
        assertEquals(4, ls.size)
        assertEquals(400L, ls[3])
    }

    @Test fun intSeriesBasic() {
        val is_ = IntSeries(2)
        is_.set(0, 42); is_.set(1, 99)
        assertEquals(42, is_[0])
        assertEquals(99, is_[1])

        is_.removeAt(0)
        assertEquals(1, is_.size)
        assertEquals(99, is_[0])
    }

    @Test fun floatSeriesBasic() {
        val fs = FloatSeries(2)
        fs.set(0, 1.1f); fs.set(1, 2.2f)
        assertEquals(1.1f, fs[0], 0.0001f)
        fs += 3.3f
        assertEquals(3, fs.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SortedSeries
    // ═══════════════════════════════════════════════════════════════════

    @Test fun sortedSeriesMaintainsOrderOnAdd() {
        val ss = SortedSeries.natural<Int>()
        ss.add(30); ss.add(10); ss.add(20); ss.add(40)
        assertEquals(4, ss.size)
        assertEquals(10, ss[0])
        assertEquals(20, ss[1])
        assertEquals(30, ss[2])
        assertEquals(40, ss[3])
    }

    @Test fun sortedSeriesAddAtIndexIsIgnored() {
        val ss = SortedSeries.natural<Int>()
        ss.add(30); ss.add(10)
        ss.add(0, 99)  // index ignored — sort order wins
        assertEquals(3, ss.size)
        assertEquals(10, ss[0])
        assertEquals(30, ss[1])
        assertEquals(99, ss[2])
    }

    @Test fun sortedSeriesRemove() {
        val ss = SortedSeries.natural<Int>()
        for (v in s_[30, 10, 20, 40]) ss.add(v)
        assertTrue(ss.remove(20))
        assertEquals(3, ss.size)
        assertEquals(10, ss[0])
        assertEquals(30, ss[1])
        assertEquals(40, ss[2])
        assertFalse(ss.remove(99))
    }

    @Test fun sortedSeriesRemoveAt() {
        val ss = SortedSeries.natural<Int>()
        for (v in s_[10, 20, 30]) ss.add(v)
        val r = ss.removeAt(1)
        assertEquals(20, r)
        assertEquals(2, ss.size)
        assertEquals(10, ss[0])
        assertEquals(30, ss[1])
    }

    @Test fun sortedSeriesSet() {
        val ss = SortedSeries.natural<Int>()
        for (v in s_[10, 20, 30]) ss.add(v)
        ss.set(1, 15)  // remove 20, insert 15 in sorted position
        assertEquals(3, ss.size)
        assertEquals(10, ss[0])
        assertEquals(15, ss[1])
        assertEquals(30, ss[2])
    }

    @Test fun sortedSeriesCustomComparator() {
        val ss = SortedSeries<Int> { a, b -> b.compareTo(a) }  // descending
        for (v in s_[10, 30, 20]) ss.add(v)
        assertEquals(30, ss[0])
        assertEquals(20, ss[1])
        assertEquals(10, ss[2])
    }

    // ═══════════════════════════════════════════════════════════════════
    // ChunkedMutableSeries
    // ═══════════════════════════════════════════════════════════════════

    @Test fun chunkedSeriesBasicAppend() {
        val cs = ChunkedMutableSeries<Int>(chunkSize = 4)
        cs.add(1); cs.add(2); cs.add(3)
        assertEquals(3, cs.size)
        assertEquals(1, cs[0])
        assertEquals(2, cs[1])
        assertEquals(3, cs[2])
    }

    @Test fun chunkedSeriesCrossesChunkBoundary() {
        val cs = ChunkedMutableSeries<Int>(chunkSize = 4)
        for (i in 0 until 10) cs.add(i)
        assertEquals(10, cs.size)
        for (i in 0 until 10) assertEquals(i, cs[i])
    }

    @Test fun chunkedSeriesSet() {
        val cs = ChunkedMutableSeries<Int>(chunkSize = 4)
        for (i in 0 until 8) cs.add(i)
        cs.set(5, 99)
        assertEquals(99, cs[5])
        // other elements unchanged
        assertEquals(4, cs[4])
        assertEquals(6, cs[6])
    }

    @Test fun chunkedSeriesRemoveAt() {
        val cs = ChunkedMutableSeries<Int>(chunkSize = 4)
        for (i in 0 until 8) cs.add(i)
        val r = cs.removeAt(3)
        assertEquals(3, r)
        assertEquals(7, cs.size)
        assertEquals(0, cs[0])
        assertEquals(2, cs[2])
        assertEquals(4, cs[3])  // 4 shifted into position 3
    }

    @Test fun chunkedSeriesClear() {
        val cs = ChunkedMutableSeries<Int>(chunkSize = 4)
        for (i in 0 until 5) cs.add(i)
        cs.clear()
        assertEquals(0, cs.size)
    }

    @Test fun chunkedSeriesInsert() {
        val cs = ChunkedMutableSeries<Int>(chunkSize = 4)
        for (i in 0 until 4) cs.add(i)  // 0,1,2,3 in chunk 0
        cs.add(1, 99)                   // insert at 1
        assertEquals(5, cs.size)
        assertEquals(0, cs[0])
        assertEquals(99, cs[1])
        assertEquals(1, cs[2])
    }

    // ═══════════════════════════════════════════════════════════════════
    // DequeSeries
    // ═══════════════════════════════════════════════════════════════════

    @Test fun dequeSeriesAddFirstAddLast() {
        val d = DequeSeries<Int>()
        d.addLast(1); d.addLast(2); d.addLast(3)
        d.addFirst(0)
        assertEquals(4, d.size)
        assertEquals(0, d[0])
        assertEquals(1, d[1])
        assertEquals(2, d[2])
        assertEquals(3, d[3])
    }

    @Test fun dequeSeriesRemoveFirstRemoveLast() {
        val d = DequeSeries<Int>()
        d.addLast(1); d.addLast(2); d.addLast(3)
        val first = d.removeFirst()
        assertEquals(1, first)
        assertEquals(2, d.size)
        assertEquals(2, d[0])

        val last = d.removeLast()
        assertEquals(3, last)
        assertEquals(1, d.size)
        assertEquals(2, d[0])
    }

    @Test fun dequeSeriesCrossesFrontBackBoundary() {
        val d = DequeSeries<Int>()
        // All additions to front
        d.addFirst(3); d.addFirst(2); d.addFirst(1)
        assertEquals(3, d.size)
        assertEquals(1, d[0])
        assertEquals(2, d[1])
        assertEquals(3, d[2])

        // Remove from back when only front exists
        val last = d.removeLast()
        assertEquals(3, last)
        assertEquals(2, d.size)
    }

    @Test fun dequeSeriesSet() {
        val d = DequeSeries<Int>()
        d.addLast(1); d.addLast(2); d.addLast(3)
        d.set(1, 99)
        assertEquals(99, d[1])
    }

    @Test fun dequeSeriesInsertMiddle() {
        val d = DequeSeries<Int>()
        d.addLast(1); d.addLast(3)
        d.add(1, 2)  // insert in middle
        assertEquals(3, d.size)
        assertEquals(1, d[0])
        assertEquals(2, d[1])
        assertEquals(3, d[2])
    }

    @Test fun dequeSeriesClear() {
        val d = DequeSeries<Int>()
        d.addFirst(1); d.addLast(2)
        d.clear()
        assertEquals(0, d.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    // GuardSeries
    // ═══════════════════════════════════════════════════════════════════

    @Test fun guardSeriesAllowsValidAdd() {
        val g = GuardSeries<Int>(guard = { it > 0 })
        g.add(5)
        assertEquals(1, g.size)
        assertEquals(5, g[0])
    }

    @Test fun guardSeriesRejectsInvalidAdd() {
        val g = GuardSeries<Int>(guard = { it > 0 })
        g.add(-1)
        assertEquals(0, g.size)
    }

    @Test fun guardSeriesRejectsInvalidSet() {
        val g = GuardSeries<Int>(guard = { it > 0 })
        g.add(10)
        g.set(0, -5)  // rejected
        assertEquals(10, g[0])
    }

    @Test fun guardSeriesRemoveAlwaysPasses() {
        val g = GuardSeries<Int>(guard = { it > 0 })
        g.add(10); g.add(20)
        assertTrue(g.remove(10))
        assertEquals(1, g.size)
        assertEquals(20, g[0])
    }

    @Test fun guardSeriesDelegatesReads() {
        val g = GuardSeries<Int>(guard = { it > 0 })
        g.add(7); g.add(14); g.add(21)
        assertEquals(3, g.size)
        assertEquals(14, g[1])
    }

    // ═══════════════════════════════════════════════════════════════════
    // JournalSeries
    // ═══════════════════════════════════════════════════════════════════

    @Test fun journalSeriesRecordsMutations() {
        val j = JournalSeries<Int>()
        j.add(10); j.add(20); j.add(30)
        assertEquals(3, j.pendingCount)
        assertEquals(3, j.size)
        assertEquals(10, j[0])
    }

    @Test fun journalSeriesCommitClearsJournal() {
        val j = JournalSeries<Int>()
        j.add(10); j.add(20)
        j.commit()
        assertEquals(0, j.pendingCount)
        assertEquals(2, j.size)  // data still there
    }

    @Test fun journalSeriesRollbackUndoesAll() {
        val j = JournalSeries<Int>()
        j.add(10); j.add(20); j.add(30)
        assertEquals(3, j.size)
        j.rollback()
        assertEquals(0, j.size)
        assertEquals(0, j.pendingCount)
    }

    @Test fun journalSeriesRollbackUndoesSet() {
        val j = JournalSeries<Int>()
        j.add(10); j.add(20); j.add(30)
        j.set(1, 99)
        assertEquals(99, j[1])
        j.rollback()
        assertEquals(0, j.size)
    }

    @Test fun journalSeriesRollbackUndoesRemove() {
        val j = JournalSeries<Int>()
        j.add(10); j.add(20); j.add(30)
        j.removeAt(1)
        assertEquals(2, j.size)
        assertEquals(10, j[0])
        assertEquals(30, j[1])
        j.rollback()
        assertEquals(0, j.size)
    }

    @Test fun journalSeriesPartialCommitThenRollback() {
        val j = JournalSeries<Int>()
        j.add(1); j.add(2)
        j.commit()
        j.add(3)
        assertEquals(1, j.pendingCount)
        j.rollback()
        assertEquals(2, j.size)  // 1,2 survive
        assertEquals(1, j[0])
        assertEquals(2, j[1])
    }

    @Test fun journalSeriesClearIsJournaled() {
        val j = JournalSeries<Int>()
        j.add(10); j.add(20); j.add(30)
        j.clear()
        assertEquals(0, j.size)
        j.rollback()
        // Items restored in reverse order: 30, 20, 10
        assertEquals(3, j.size)
        assertEquals(10, j[0])
        assertEquals(20, j[1])
        assertEquals(30, j[2])
    }

    // ═══════════════════════════════════════════════════════════════════
    // SortedSeries (merged with MergeMutableSeries)
    // ═══════════════════════════════════════════════════════════════════

    @Test fun sortedSeriesBatchInsertBelowThreshold() {
        val ss = SortedSeries<Int>(
            mergeThreshold = 10,
            comparator = { a, b -> a.compareTo(b) },
        )
        ss.add(5); ss.add(3); ss.add(1)  // below threshold — pending only
        assertEquals(0, ss.size)          // not compacted yet
    }

    @Test fun sortedSeriesCompactsAtThreshold() {
        val ss = SortedSeries<Int>(
            mergeThreshold = 3,
            comparator = { a, b -> a.compareTo(b) },
        )
        ss.add(30); ss.add(10); ss.add(20)  // hits threshold, compacts
        assertEquals(3, ss.size)
        assertEquals(10, ss[0])
        assertEquals(20, ss[1])
        assertEquals(30, ss[2])
    }

    @Test fun sortedSeriesFlushForcesCompact() {
        val ss = SortedSeries<Int>(
            mergeThreshold = 100,
            comparator = { a, b -> a.compareTo(b) },
        )
        ss.add(5); ss.add(3); ss.add(1)
        assertEquals(0, ss.size)
        ss.flush()
        assertEquals(3, ss.size)
        assertEquals(1, ss[0])
        assertEquals(3, ss[1])
        assertEquals(5, ss[2])
    }

    @Test fun sortedSeriesMergeMaintainsSortAcrossBatches() {
        val ss = SortedSeries<Int>(
            mergeThreshold = 4,
            comparator = { a, b -> a.compareTo(b) },
        )
        // Batch 1: 30, 10, 20, 40 → compacts → 10, 20, 30, 40
        ss.add(30); ss.add(10); ss.add(20); ss.add(40)
        assertEquals(4, ss.size)
        assertEquals(10, ss[0]); assertEquals(20, ss[1])
        assertEquals(30, ss[2]); assertEquals(40, ss[3])

        // Batch 2: 5, 15, 25, 35 → compacts, merges with [10,20,30,40]
        ss.add(5); ss.add(15); ss.add(25); ss.add(35)
        assertEquals(8, ss.size)
        // Final sorted: 5, 10, 15, 20, 25, 30, 35, 40
        assertEquals(5, ss[0])
        assertEquals(10, ss[1])
        assertEquals(15, ss[2])
        assertEquals(20, ss[3])
        assertEquals(25, ss[4])
        assertEquals(30, ss[5])
        assertEquals(35, ss[6])
        assertEquals(40, ss[7])
    }

    @Test fun sortedSeriesRemove() {
        val ss = SortedSeries<Int>(
            mergeThreshold = 3,
            comparator = { a, b -> a.compareTo(b) },
        )
        ss.add(10); ss.add(20); ss.add(30)
        assertTrue(ss.remove(20))
        assertEquals(2, ss.size)
        assertEquals(10, ss[0])
        assertEquals(30, ss[1])
    }

    @Test fun sortedSeriesClear() {
        val ss = SortedSeries<Int>(
            mergeThreshold = 2,
            comparator = { a, b -> a.compareTo(b) },
        )
        ss.add(10); ss.add(20)
        assertEquals(2, ss.size)
        ss.clear()
        assertEquals(0, ss.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Cross-strategy: all implement MutableSeries<T> contract
    // ═══════════════════════════════════════════════════════════════════

    @Test fun allStrategiesSatisfyMutableSeriesContract() {
        // Verify that every strategy can be assigned to MutableSeries<Int>
        val strategies: Series<MutableSeries<Int>> = s_[
            RingSeries<Int>(4).also { it.add(1) },
            IntSeries(1).also { it.set(0, 1) },
            SortedSeries.natural<Int>().also { it.add(1) },
            ChunkedMutableSeries<Int>().also { it.add(1) },
            DequeSeries<Int>().also { it.addLast(1) },
            GuardSeries<Int>(guard = { true }).also { it.add(1) },
            JournalSeries<Int>().also { it.add(1) },
            SortedSeries(mergeThreshold = 1, comparator = { a, b -> a.compareTo(b) }).also { it.add(1); it.flush() },
        ]

        // All report size and first element correctly
        for (s in strategies.view) {
            assertTrue(s.size >= 1, "size should be >= 1 for strategy ${s::class}")
            assertEquals(1, s[0], "first element should be 1 for ${s::class}")
        }
    }
}
