package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.TpchLineitem
import borg.trikeshed.lib.*
import borg.trikeshed.lib.intp.Comparison
import borg.trikeshed.parse.kursive.sql.SqlParser
import borg.trikeshed.parse.kursive.sql.plan
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.random.Random
import kotlin.test.*

/** The native kernels must return exactly what the Kotlin kernels return, bit for bit. */
class ColumnOperationsFfmTest {
    val ffi: ColumnOperations? = JvmColumnOperations.available()
    val kotlin: ColumnOperations = KotlinColumnOperations
    val random = Random(7)
    val n = 10_001

    /** `buildJvmKernels` sets this wherever a C compiler exists; without one only the Kotlin provider runs. */
    private fun provider(): ColumnOperations {
        assumeTrue(java.lang.System.getProperty("trikeshed.libkernels") != null, "no C compiler on this host")
        return assertNotNull(ffi, "libtrikeshed_kernels should load from -Dtrikeshed.libkernels")
    }

    private val doubles = DoubleArray(n) { (random.nextInt(0, 200) - 100) / 8.0 }
    private val longs = LongArray(n) { random.nextLong(-50, 50) }

    @Test fun selectionKernelsAgreeForEveryComparison() {
        val native = provider()
        for (op in Comparison.entries) {
            val a = IntArray(n); val b = IntArray(n)
            val ma = kotlin.whereF64(doubles, n, op.ordinal, 3.5, a)
            val mb = native.whereF64(doubles, n, op.ordinal, 3.5, b)
            assertEquals(ma, mb, "whereF64 $op")
            assertContentEquals(a.copyOf(ma), b.copyOf(mb), "whereF64 $op")
            val ra = kotlin.refineF64(doubles, a, ma, op.ordinal, -2.0, a)
            val rb = native.refineF64(doubles, b, mb, op.ordinal, -2.0, b)
            assertContentEquals(a.copyOf(ra), b.copyOf(rb), "refineF64 $op")

            val la = IntArray(n); val lb = IntArray(n)
            val pa = kotlin.whereI64(longs, n, op.ordinal, 3L, la)
            val pb = native.whereI64(longs, n, op.ordinal, 3L, lb)
            assertContentEquals(la.copyOf(pa), lb.copyOf(pb), "whereI64 $op")
            val qa = kotlin.refineI64(longs, la, pa, op.ordinal, -10L, la)
            val qb = native.refineI64(longs, lb, pb, op.ordinal, -10L, lb)
            assertContentEquals(la.copyOf(qa), lb.copyOf(qb), "refineI64 $op")
        }
    }

    @Test fun reductionsAndArithmeticAreBitIdentical() {
        val native = provider()
        val sel = IntArray(n) { it }
        assertEquals(kotlin.sumF64(doubles, sel, n).toRawBits(), native.sumF64(doubles, sel, n).toRawBits())
        assertEquals(kotlin.sumI64(longs, sel, n), native.sumI64(longs, sel, n))
        val picked = IntArray(n / 3) { it * 3 }
        val xa = DoubleArray(picked.size); val xb = DoubleArray(picked.size)
        kotlin.mulF64(doubles, doubles, picked, picked.size, xa)
        native.mulF64(doubles, doubles, picked, picked.size, xb)
        assertContentEquals(xa, xb)
        kotlin.affineF64(doubles, -0.07, 1.0, picked, picked.size, xa)
        native.affineF64(doubles, -0.07, 1.0, picked, picked.size, xb)
        assertContentEquals(xa, xb)
    }

    @Test fun groupingAgrees() {
        val native = provider()
        val keys = LongArray(n) { (it * 2654435761L) % 97 }
        val sel = IntArray(n) { it }
        fun group(ops: ColumnOperations): Triple<Int, IntArray, IntArray> {
            val table = IntArray(borg.trikeshed.lib.longp.tableSizeFor(n))
            val ids = IntArray(n); val first = IntArray(n)
            val g = ops.groupIdsI64(keys, sel, n, ids, first, table, LongArray(table.size))
            return Triple(g, ids, first.copyOf(g))
        }
        val (ga, idsA, firstA) = group(kotlin)
        val (gb, idsB, firstB) = group(native)
        assertEquals(97, ga)
        assertEquals(ga, gb)
        assertContentEquals(idsA, idsB)
        assertContentEquals(firstA, firstB)
        val sa = DoubleArray(ga); val sb = DoubleArray(ga)
        kotlin.sumByGroupF64(doubles, sel, idsA, n, sa); native.sumByGroupF64(doubles, sel, idsA, n, sb)
        assertContentEquals(sa, sb)
        val la = LongArray(ga); val lb = LongArray(ga)
        kotlin.sumByGroupI64(longs, sel, idsA, n, la); native.sumByGroupI64(longs, sel, idsA, n, lb)
        assertContentEquals(la, lb)
        val ca = LongArray(ga); val cb = LongArray(ga)
        kotlin.countByGroup(idsA, n, ca); native.countByGroup(idsA, n, cb)
        assertContentEquals(ca, cb)
    }

    @Test fun cosineAgrees() {
        val native = provider()
        val dims = 33
        val rows = 500
        val flat = FloatArray(rows * dims) { random.nextFloat() - 0.5f }
        val probe = FloatArray(dims) { random.nextFloat() - 0.5f }
        val a = FloatArray(rows); val b = FloatArray(rows)
        kotlin.cosineF32(flat, rows, dims, probe, a)
        native.cosineF32(flat, rows, dims, probe, b)
        assertContentEquals(a, b)
    }

    @Test fun dotAndL2Agree() {
        val native = provider()
        for (dims in intArrayOf(1, 7, 33, 128)) {
            val rows = 300
            val flat = FloatArray(rows * dims) { random.nextFloat() - 0.5f }
            val probe = FloatArray(dims) { random.nextFloat() - 0.5f }
            val a = FloatArray(rows); val b = FloatArray(rows)
            kotlin.dotF32(flat, rows, dims, probe, a); native.dotF32(flat, rows, dims, probe, b)
            assertContentEquals(a, b, "dot $dims")
            kotlin.l2SquaredF32(flat, rows, dims, probe, a); native.l2SquaredF32(flat, rows, dims, probe, b)
            assertContentEquals(a, b, "l2 $dims")
        }
    }

    @Test fun vectorStatementsAgree() {
        val native = provider()
        val dims = 24
        val rows = 3_000
        val vectors = VectorColumn.of(dims, rows) { r -> FloatArray(dims) { d -> ((r * 7 + d * 13) % 101) / 50f - 1f } }
        val data = ColumnCursor.of(
            arrayOf(ColumnMeta("id", IOMemento.IoLong), vectorMeta("embedding")),
            arrayOf(DenseColumn(LongArray(rows) { it.toLong() }), DenseColumn(vectors)),
        )
        val probe = "[" + (0 until dims).joinToString(",") { ((it * 5) % 11 / 10f).toString() } + "]"
        for (op in listOf("<=>", "<->", "<#>")) {
            val sql = "SELECT id, embedding $op '$probe' AS d FROM t ORDER BY d LIMIT 20"
            fun run(ops: ColumnOperations): List<List<Any?>> {
                val c = SqlParser.parse(sql)!!.plan({ data }, ops).result
                return (0 until c.size).map { r -> c[r].values.let { v -> (0 until v.size).map { v[it] } } }
            }
            assertEquals(run(kotlin), run(native), op)
        }
    }

    @Test fun wholeStatementsAgree() {
        val native = provider()
        val data = TpchLineitem.generate(50_000)
        fun run(sql: String, ops: ColumnOperations): List<List<Any?>> {
            val c = SqlParser.parse(sql)!!.plan({ data }, ops).result
            return (0 until c.size).map { r -> c[r].values.let { v -> (0 until v.size).map { v[it] } } }
        }
        for (sql in listOf(TpchLineitem.Q1, TpchLineitem.Q6)) assertEquals(run(sql, kotlin), run(sql, native), sql)
    }

    @Test fun aMissingLibraryIsNoProvider() {
        assumeTrue(java.lang.System.getProperty("trikeshed.libkernels") == null)
        assertNull(ffi)
    }
}
