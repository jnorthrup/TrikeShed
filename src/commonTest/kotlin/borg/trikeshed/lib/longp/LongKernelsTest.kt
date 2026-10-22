package borg.trikeshed.lib.longp

import borg.trikeshed.lib.intp.Comparison
import borg.trikeshed.lib.intp.identitySelection
import kotlin.test.*

class LongKernelsTest {
    @Test fun selectionChain() {
        val v = longArrayOf(5, 1, 9, 3, 7, 3)
        val sel = IntArray(6)
        var m = where(v, 6, Comparison.GT, 2, sel)
        m = refine(v, sel, m, Comparison.NE, 7, sel)
        assertEquals(listOf(0, 2, 3, 5), sel.take(m))
        assertEquals(20L, sum(v, sel, m))
        assertEquals(3L, min(v, sel, m))
        assertEquals(9L, max(v, sel, m))
    }

    @Test fun groupIdsNumberByFirstOccurrenceAndSurviveCollisions() {
        // Keys chosen to include a duplicate-heavy run and large magnitudes that exercise the hash.
        val keys = LongArray(5000) { (it % 37).toLong() * 1_000_003L - 5L }
        val n = keys.size
        val table = IntArray(tableSizeFor(n))
        val ids = IntArray(n)
        val first = IntArray(n)
        val groups = groupIds(keys, identitySelection(n), n, ids, first, table, LongArray(table.size))
        assertEquals(37, groups)
        for (r in 0 until n) assertEquals(r % 37, ids[r])
        for (g in 0 until groups) assertEquals(g, first[g])
        val counts = LongArray(groups)
        countByGroup(ids, n, counts)
        assertTrue(counts.all { it == 135L || it == 136L })
        val sums = LongArray(groups)
        sumByGroup(LongArray(n) { 1L }, identitySelection(n), ids, n, sums)
        assertEquals(counts.toList(), sums.toList())
    }

    @Test fun hashJoinMatchesNestedLoop() {
        val build = longArrayOf(10, 20, 10, 30, 20, 10)
        val probeKeys = longArrayOf(20, 40, 10, 10)
        val head = IntArray(tableSizeFor(build.size)) { -1 }
        val next = IntArray(build.size)
        hashBuild(build, build.size, head, next)
        val outP = IntArray(64)
        val outB = IntArray(64)
        val c = probe(probeKeys, probeKeys.size, build, head, next, outP, outB)
        val expected = ArrayList<Pair<Int, Int>>()
        for (p in probeKeys.indices) for (b in build.indices) if (probeKeys[p] == build[b]) expected += p to b
        assertEquals(expected, (0 until c).map { outP[it] to outB[it] })
    }

    @Test fun probeReportsFullOutputBuffers() {
        val build = LongArray(10) { 1 }
        val head = IntArray(16) { -1 }
        val next = IntArray(10)
        hashBuild(build, 10, head, next)
        val c = probe(longArrayOf(1), 1, build, head, next, IntArray(4), IntArray(4))
        assertTrue(c < 0)
    }
}
