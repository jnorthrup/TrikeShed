package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.userspace.nio.platform.spi.ColumnOperations
import borg.trikeshed.userspace.nio.platform.spi.KotlinColumnOperations
import kotlin.test.*

/** pgvector distances over a [VectorColumn] run on the float kernels and answer what the closure path answers. */
class VectorRoutingTest {
    val dims = 16
    val n = 2_000

    /** Deterministic components in [-1, 1). */
    fun component(row: Int, d: Int): Float {
        var x = (row * 31 + d * 17 + 7).toLong() * 6364136223846793005L + 1442695040888963407L
        x = x xor (x ushr 29)
        return ((x and 0xFFFF) / 32768.0f) - 1f
    }

    fun table(nulls: Set<Int> = emptySet()): ColumnCursor {
        val vectors = VectorColumn.of(dims, n) { r -> if (r in nulls) null else FloatArray(dims) { d -> component(r, d) } }
        val valid = if (nulls.isEmpty()) null else BooleanArray(n) { it !in nulls }
        return ColumnCursor.of(
            arrayOf(ColumnMeta("id", IOMemento.IoLong), vectorMeta("embedding")),
            arrayOf(DenseColumn(LongArray(n) { it.toLong() }), DenseColumn(vectors, valid)),
        )
    }

    fun boxed(c: ColumnCursor): Cursor = c.size j { r: Int -> c[r] }
    fun rows(c: Cursor): List<List<Any?>> = (0 until c.size).map { r -> c[r].values.let { v -> (0 until v.size).map { v[it] } } }
    fun run(sql: String, source: Cursor, ops: ColumnOperations = KotlinColumnOperations): Cursor =
        SqlParser.parse(sql)!!.plan({ source }, ops).result

    val probe = "[" + (0 until 16).joinToString(",") { component(1_999, it).toString() } + "]"

    @Test fun eachDistanceRunsOnItsKernelAndEqualsTheClosurePath() {
        val data = table()
        for (op in listOf("<=>", "<->", "<#>")) {
            val counting = CountingOps()
            val sql = "SELECT id, embedding $op '$probe' AS d FROM items ORDER BY d LIMIT 10"
            val fast = run(sql, data, counting)
            val slow = run(sql, boxed(data))
            assertEquals(1, counting.calls, op)
            assertEquals(10, fast.size, op)
            assertEquals(rows(slow), rows(fast), op)
            assertEquals(1999L, fast[0].values[0], "$op: the probe is its own nearest row")
        }
    }

    @Test fun theProbeMayCastAndMayComeFirst() {
        val data = table()
        val counting = CountingOps()
        val sql = "SELECT id FROM items ORDER BY '$probe'::vector <=> embedding LIMIT 5"
        assertEquals(rows(run(sql, boxed(data))), rows(run(sql, data, counting)))
        assertEquals(1, counting.calls)
    }

    @Test fun nullVectorsStayNull() {
        val data = table(nulls = setOf(3, 1_999))
        val sql = "SELECT id, embedding <-> '$probe' AS d FROM items WHERE id > 0 ORDER BY d LIMIT 5"
        val counting = CountingOps()
        val fast = run(sql, data, counting)
        assertEquals(rows(run(sql, boxed(data))), rows(fast))
        assertEquals(2, counting.calls, "one selection kernel for id > 0, one l2 pass")
        val all = run("SELECT id, embedding <-> '$probe' FROM items", data)
        assertNull(all[3].values[1])
        assertNull(all[1_999].values[1])
        assertNotNull(all[4].values[1])
    }

    @Test fun aWidthMismatchIsTheClosurePathsErrorNotAKernelOverrun() {
        val data = table()
        val counting = CountingOps()
        val e = assertFailsWith<IllegalArgumentException> { rows(run("SELECT embedding <=> '[1,0]' FROM items", data, counting)) }
        assertTrue(e.message!!.contains("different vector dimensions 16 and 2"), e.message)
        assertEquals(0, counting.calls)
    }

    @Test fun aNonConstantProbeStaysOnTheClosurePath() {
        val data = table()
        val counting = CountingOps()
        val sql = "SELECT id FROM items ORDER BY embedding <=> embedding, id LIMIT 3"
        assertEquals(rows(run(sql, boxed(data))), rows(run(sql, data, counting)))
        assertEquals(0, counting.calls)
    }

    @Test fun denseOfAGenericCursorPacksVectorCellsIntoAVectorColumn() {
        val data = table(nulls = setOf(2))
        val packed = boxed(data).dense(1)
        val column = assertIs<VectorColumn>(packed.data)
        assertEquals(dims, column.dims)
        assertEquals(n, column.rows)
        assertContentEquals((data.dense(1).data as VectorColumn).flat, column.flat)
        assertEquals(false, packed.valid!![2])
        assertNull(packed[2])
        assertContentEquals(FloatArray(dims) { component(5, it) }, packed[5] as FloatArray)
    }

    @Test fun gatherCopiesTheSelectedVectorsInOrder() {
        val column = (table().dense(1).data as VectorColumn)
        val picked = column.gather(intArrayOf(7, 3), 2)
        assertEquals(2, picked.rows)
        assertContentEquals(column[7], picked[0])
        assertContentEquals(column[3], picked[1])
    }

    @Test fun embeddingsBecomeACursorTheDistancesRunOn() {
        val rows: Series<Join<String, Series<Double>>> = 3 j { i: Int ->
            "doc$i" j (3 j { d: Int -> if (d == i) 1.0 else 0.0 })
        }
        val cursor = embeddingCursor(rows)
        assertEquals(listOf("id", "embedding"), cursor.columnNames.view.map { it.toString() })
        assertTrue(cursor.metas[1].isVector)
        val counting = CountingOps()
        val nearest = run("SELECT id FROM docs ORDER BY embedding <=> '[0,1,0]' LIMIT 1", cursor, counting)
        assertEquals("doc1", nearest[0].values[0])
        assertEquals(1, counting.calls)
        assertEquals(0, embeddingCursor(0 j { _: Int -> error("empty") }).rows)
        assertFailsWith<IllegalArgumentException> { embeddingCursor(2 j { i: Int -> "x$i" j (if (i == 0) 2 else 3).let { w -> w j { _: Int -> 0.5 } } }) }
    }
}
