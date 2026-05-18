package borg.trikeshed.miniduck.query

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.miniduck.*
import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * RED test: GROUP BY aggregation over MiniCursor.
 *
 * SQL equivalent: SELECT dept, COUNT(*), SUM(salary), AVG(salary) FROM emp GROUP BY dept
 *
 * In MiniDuck, this is a cursor transform:
 *   cursor.groupBy(keyExtractor, agg1, agg2, ...)
 *
 * Aggregations: Count, Sum, Avg, Min, Max — each takes a column name (or "*" for Count).
 * Output is a new MiniCursor with one row per group, columns = [groupKey, agg1, agg2, ...].
 *
 * Donor: DuckDB hash aggregate, CouchDB reduce/re-reduce, Spark groupBy.
 */
class BlockAggregateQueryTest {

   fun empCursor(): Cursor {
        val rows = listOf(
            DocRowVec(listOf("name", "dept", "salary"), listOf("alice", "eng", 120000)),
            DocRowVec(listOf("name", "dept", "salary"), listOf("bob", "eng", 110000)),
            DocRowVec(listOf("name", "dept", "salary"), listOf("carol", "sales", 90000)),
            DocRowVec(listOf("name", "dept", "salary"), listOf("dave", "sales", 95000)),
            DocRowVec(listOf("name", "dept", "salary"), listOf("eve", "eng", 130000)),
            DocRowVec(listOf("name", "dept", "salary"), listOf("frank", "hr", 80000)),
        )
        return rows.size j { idx: Int -> rows[idx] as RowVec }
    }

    // ── COUNT ────────────────────────────────────────────────────────────

    @Test
    fun groupByCountReturnsOneRowPerGroup() {
        val cursor = empCursor()
        val grouped = cursor.groupBy("dept", Agg.count())

        assertEquals(3, grouped.size) // eng, sales, hr
    }

    @Test
    fun groupByCountValues() {
        val cursor = empCursor()
        val grouped = cursor.groupBy("dept", Agg.count())

        // Find each dept's count
        val counts = mutableMapOf<String, Long>()
        for (i in 0 until grouped.size) {
            val row = grouped.at(i) as DocRowVec
            val dept = row["dept"] as String
            val cnt = (row["count"] as Number).toLong()
            counts[dept] = cnt
        }
        assertEquals(3L, counts["eng"])
        assertEquals(2L, counts["sales"])
        assertEquals(1L, counts["hr"])
    }

    // ── SUM ──────────────────────────────────────────────────────────────

    @Test
    fun groupBySum() {
        val cursor = empCursor()
        val grouped = cursor.groupBy("dept", Agg.sum("salary"))

        val sums = mutableMapOf<String, Double>()
        for (i in 0 until grouped.size) {
            val row = grouped.at(i) as DocRowVec
            sums[row["dept"] as String] = (row["sum_salary"] as Number).toDouble()
        }
        assertEquals(360000.0, sums["eng"]!!, 1.0)  // 120k + 110k + 130k
        assertEquals(185000.0, sums["sales"]!!, 1.0) // 90k + 95k
        assertEquals(80000.0, sums["hr"]!!, 1.0)
    }

    // ── AVG ──────────────────────────────────────────────────────────────

    @Test
    fun groupByAvg() {
        val cursor = empCursor()
        val grouped = cursor.groupBy("dept", Agg.avg("salary"))

        val avgs = mutableMapOf<String, Double>()
        for (i in 0 until grouped.size) {
            val row = grouped.at(i) as DocRowVec
            avgs[row["dept"] as String] = (row["avg_salary"] as Number).toDouble()
        }
        assertEquals(120000.0, avgs["eng"]!!, 1.0)   // (120k+110k+130k)/3
        assertEquals(92500.0, avgs["sales"]!!, 1.0)   // (90k+95k)/2
        assertEquals(80000.0, avgs["hr"]!!, 1.0)
    }

    // ── MIN / MAX ────────────────────────────────────────────────────────

    @Test
    fun groupByMinMax() {
        val cursor = empCursor()
        val grouped = cursor.groupBy("dept", Agg.min("salary"), Agg.max("salary"))

        val mins = mutableMapOf<String, Double>()
        val maxs = mutableMapOf<String, Double>()
        for (i in 0 until grouped.size) {
            val row = grouped.at(i) as DocRowVec
            mins[row["dept"] as String] = (row["min_salary"] as Number).toDouble()
            maxs[row["dept"] as String] = (row["max_salary"] as Number).toDouble()
        }
        assertEquals(110000.0, mins["eng"]!!, 1.0)
        assertEquals(130000.0, maxs["eng"]!!, 1.0)
        assertEquals(90000.0, mins["sales"]!!, 1.0)
        assertEquals(95000.0, maxs["sales"]!!, 1.0)
    }

    // ── Multiple aggregations at once ────────────────────────────────────

    @Test
    fun groupByMultipleAggregations() {
        val cursor = empCursor()
        val grouped = cursor.groupBy("dept", Agg.count(), Agg.sum("salary"), Agg.avg("salary"))

        // Output columns: dept, count, sum_salary, avg_salary
        val row0 = grouped.at(0) as DocRowVec
        assertTrue(row0.keys.contains("dept"))
        assertTrue(row0.keys.contains("count"))
        assertTrue(row0.keys.contains("sum_salary"))
        assertTrue(row0.keys.contains("avg_salary"))
    }

    // ── Empty cursor ─────────────────────────────────────────────────────

    @Test
    fun groupByEmptyCursorReturnsEmpty() {
        val cursor = emptyCursor()
        val grouped = cursor.groupBy("dept", Agg.count())
        assertEquals(0, grouped.size)
    }

    // ── Single group ─────────────────────────────────────────────────────

    @Test
    fun groupBySingleGroup() {
        val rows = listOf(
            DocRowVec(listOf("cat", "val"), listOf("x", 1)),
            DocRowVec(listOf("cat", "val"), listOf("x", 2)),
            DocRowVec(listOf("cat", "val"), listOf("x", 3)),
        )
        val cursor = rows.size j { idx: Int -> rows[idx] as RowVec }
        val grouped = cursor.groupBy("cat", Agg.count(), Agg.sum("val"))

        assertEquals(1, grouped.size)
        val row = grouped.at(0) as DocRowVec
        assertEquals(3L, (row["count"] as Number).toLong())
        assertEquals(6.0, (row["sum_val"] as Number).toDouble(), 1e-10)
    }

    // ── HAVING: filter after aggregation ─────────────────────────────────

    @Test
    fun groupByWithHavingFilter() {
        val cursor = empCursor()
        val grouped = cursor
            .groupBy("dept", Agg.count(), Agg.sum("salary"))
            .where(Gt("sum_salary", 180000.0))

        // Only eng (360k) and sales (185k) should pass; hr (80k) filtered out
        assertEquals(2, grouped.size)
    }

    // ── Null values in group key ─────────────────────────────────────────

    @Test
    fun groupByNullKeyGroupsTogether() {
        val rows = listOf(
            DocRowVec(listOf("cat", "val"), listOf(null, 1)),
            DocRowVec(listOf("cat", "val"), listOf(null, 2)),
            DocRowVec(listOf("cat", "val"), listOf("a", 3)),
        )
        val cursor = rows.size j { idx: Int -> rows[idx] as RowVec }
        val grouped = cursor.groupBy("cat", Agg.count())

        assertEquals(2, grouped.size) // null group + "a" group
    }
}
