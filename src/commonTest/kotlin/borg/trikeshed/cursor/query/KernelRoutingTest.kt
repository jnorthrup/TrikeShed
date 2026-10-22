package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.userspace.nio.platform.spi.ColumnOperations
import borg.trikeshed.userspace.nio.platform.spi.KotlinColumnOperations
import kotlin.test.*

/** A provider that counts the kernels the executor actually called. */
class CountingOps(val inner: ColumnOperations = KotlinColumnOperations) : ColumnOperations by inner {
    var calls = 0
    override fun whereF64(values: DoubleArray, n: Int, op: Int, operand: Double, out: IntArray): Int { calls++; return inner.whereF64(values, n, op, operand, out) }
    override fun whereI64(values: LongArray, n: Int, op: Int, operand: Long, out: IntArray): Int { calls++; return inner.whereI64(values, n, op, operand, out) }
    override fun sumF64(values: DoubleArray, sel: IntArray, m: Int): Double { calls++; return inner.sumF64(values, sel, m) }
    override fun affineF64(a: DoubleArray, scale: Double, shift: Double, sel: IntArray, m: Int, out: DoubleArray) { calls++; inner.affineF64(a, scale, shift, sel, m, out) }
    override fun groupIdsI64(keys: LongArray, sel: IntArray, m: Int, ids: IntArray, firstRow: IntArray, table: IntArray, tableKeys: LongArray): Int { calls++; return inner.groupIdsI64(keys, sel, m, ids, firstRow, table, tableKeys) }
}

class KernelRoutingTest {
    val data = TpchLineitem.generate(20_000)
    /** The same rows behind a plain Join: not a ColumnCursor, so every stage takes the closure path. */
    val boxed: Cursor = data.size j { r: Int -> data[r] }

    fun rows(c: Cursor): List<List<Any?>> = (0 until c.size).map { r -> c[r].values.let { v -> (0 until v.size).map { v[it] } } }
    fun run(sql: String, source: Cursor, ops: ColumnOperations): Cursor =
        SqlParser.parse(sql)!!.plan({ source }, ops).result

    @Test fun q6KernelsEqualTheClosurePath() {
        val counting = CountingOps()
        val fast = run(TpchLineitem.Q6, data, counting)
        val slow = run(TpchLineitem.Q6, boxed, KotlinColumnOperations)
        assertTrue(counting.calls > 0, "the kernels ran")
        assertEquals(rows(slow), rows(fast))
        assertEquals(listOf("revenue"), fast.columnNames.view.map { it.toString() })
        val revenue = fast[0].values[0] as Double
        assertTrue(revenue > 0.0)
    }

    @Test fun q1KernelsEqualTheClosurePath() {
        val counting = CountingOps()
        val fast = run(TpchLineitem.Q1, data, counting)
        val slow = run(TpchLineitem.Q1, boxed, KotlinColumnOperations)
        assertTrue(counting.calls > 0, "the kernels ran")
        assertEquals(rows(slow), rows(fast))
        // The generator yields (A,F) (N,O) (R,F), in ORDER BY order.
        assertEquals(listOf("A", "N", "R"), (0 until fast.size).map { fast[it].values[0] })
        assertEquals(data.rows.toLong(), (0 until fast.size).sumOf { fast[it].values[9] as Long } + unshipped())
    }

    private fun unshipped(): Long =
        data.dense(4).let { d -> (d.data as LongArray).count { it > 10471 }.toLong() }

    @Test fun dictionaryEqualityAndOrderLimitRoute() {
        val counting = CountingOps()
        val sql = "SELECT quantity, extendedprice FROM lineitem WHERE returnflag = 'R' AND quantity > 40 " +
            "ORDER BY extendedprice DESC LIMIT 5 OFFSET 1"
        val fast = run(sql, data, counting)
        val slow = run(sql, boxed, KotlinColumnOperations)
        assertEquals(5, fast.size)
        assertEquals(rows(slow), rows(fast))
        val prices = (0 until 5).map { fast[it].values[1] as Double }
        assertEquals(prices.sortedDescending(), prices)
    }

    @Test fun keyedWidenOnLongKeysRoutes() {
        val orders = ColumnCursor.of(
            arrayOf(ColumnMeta("id", IOMemento.IoLong), ColumnMeta("who", IOMemento.IoLong)),
            arrayOf(DenseColumn(longArrayOf(1, 2, 3)), DenseColumn(longArrayOf(100, 200, 300))),
        )
        val items = ColumnCursor.of(
            arrayOf(ColumnMeta("order", IOMemento.IoLong), ColumnMeta("n", IOMemento.IoDouble)),
            arrayOf(DenseColumn(longArrayOf(2, 1, 2, 9)), DenseColumn(doubleArrayOf(1.0, 2.0, 3.0, 4.0))),
        )
        val tables = mapOf("o" to orders, "i" to items)
        val sql = "SELECT i.n, o.who FROM i JOIN o ON i.order = o.id ORDER BY i.n"
        val fast = SqlParser.parse(sql)!!.plan({ tables.getValue(it) }).result
        val slow = SqlParser.parse(sql)!!.plan({ t -> tables.getValue(t).let { c -> c.size j { r: Int -> c[r] } } }).result
        assertEquals(listOf(listOf(1.0, 200L), listOf(2.0, 100L), listOf(3.0, 200L)), rows(fast))
        assertEquals(rows(slow), rows(fast))
    }

    @Test fun nullsAndFractionalOperandsFallBackWithoutChangingTheAnswer() {
        val c = ColumnCursor.of(
            arrayOf(ColumnMeta("n", IOMemento.IoLong), ColumnMeta("x", IOMemento.IoDouble)),
            arrayOf(DenseColumn(longArrayOf(1, 2, 3), booleanArrayOf(true, false, true)), DenseColumn(doubleArrayOf(0.5, 1.5, 2.5))),
        )
        val counting = CountingOps()
        val withNull = run("SELECT n FROM t WHERE n >= 1", c, counting)
        assertEquals(listOf(listOf<Any?>(1L), listOf(3L)), rows(withNull))
        assertEquals(0, counting.calls, "a null in the column keeps SQL null semantics on the closure path")
        val fractional = run("SELECT x FROM t WHERE n > 1.5", c, counting)
        assertEquals(listOf(listOf<Any?>(2.5)), rows(fractional))
    }
}
