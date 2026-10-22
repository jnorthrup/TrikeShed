package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import kotlin.test.*

/** pgvector's operator surface and `::` casts over Confix arrays, on the closure path. */
class VectorSqlTest {
    val items = """[
        {"id": 1, "embedding": [1, 0, 0]},
        {"id": 2, "embedding": [0, 1, 0]},
        {"id": 3, "embedding": [0.9, 0.1, 0]},
        {"id": 4, "embedding": [0, 0, 1]}
    ]"""
    fun resolve(name: String): Cursor = confixDoc(items).index.facet(ConfixIndexK.Columns)
    fun run(sql: String): Cursor = assertNotNull(SqlParser.parse(sql), sql).plan(::resolve).result
    fun rows(c: Cursor): List<List<Any?>> = (0 until c.size).map { r -> c[r].values.let { v -> (0 until v.size).map { v[it] } } }

    @Test fun cosineDistanceOrdersNearestFirst() {
        assertEquals(listOf(listOf<Any?>(1L), listOf<Any?>(3L)), rows(run("SELECT id FROM items ORDER BY embedding <=> '[1,0,0]' LIMIT 2")))
    }

    @Test fun orderByAnExpressionNeedsNoSelectItemAndSelectStarHidesIt() {
        val c = run("SELECT * FROM items ORDER BY embedding <=> '[1,0,0]' LIMIT 2")
        assertEquals(listOf("id", "embedding"), c.columnNames.view.map { it.toString() })
        assertEquals(listOf(1L, 3L), (0 until c.size).map { c[it].values[0] })
    }

    @Test fun l2DistanceIsReturnedAndTiesKeepSourceOrder() {
        val c = run("SELECT id, embedding <-> '[1,0,0]' AS d FROM items ORDER BY d LIMIT 3")
        assertEquals(listOf(1L, 3L, 2L), (0 until c.size).map { c[it].values[0] })
        assertEquals(0.0, c[0].values[1] as Double)
        assertEquals(0.1414213, c[1].values[1] as Double, 1e-6)
        assertEquals(1.4142135, c[2].values[1] as Double, 1e-6)
        assertEquals("d", c.columnNames[1].toString())
    }

    @Test fun negativeInnerProductSortsMostSimilarFirst() {
        val c = run("SELECT id FROM items ORDER BY embedding <#> '[1,0,0]' LIMIT 1")
        assertEquals(1L, c[0].values[0])
        assertEquals(-1.0, SqlRuntime.negInnerProduct("[1,0,0]", "[1,0,0]"))
    }

    @Test fun cosineOfAZeroVectorIsDistanceOne() {
        assertEquals(1.0, SqlRuntime.cosineDistance("[0,0]", "[1,1]"))
    }

    @Test fun mismatchedDimensionsAreAnError() {
        val e = assertFailsWith<IllegalArgumentException> { rows(run("SELECT embedding <=> '[1,0]' FROM items")) }
        assertTrue(e.message!!.contains("different vector dimensions 3 and 2"), e.message)
    }

    @Test fun badVectorTextIsAnErrorWhenRead() {
        val e = assertFailsWith<IllegalStateException> { rows(run("SELECT id FROM items ORDER BY embedding <=> '[1,x,0]'")) }
        assertTrue(e.message!!.contains("invalid input syntax for type vector"), e.message)
    }

    @Test fun castsConvertBetweenTheSharedTypes() {
        val c = run("SELECT '12'::int + 1, 3::double / 2, id::text || '!', 'T'::bool FROM items WHERE id::int > 2 AND '0.5'::float < 1 ORDER BY id")
        assertEquals(listOf(listOf(13L, 1.5, "3!", true), listOf(13L, 1.5, "4!", true)), rows(c))
        assertNull(SqlRuntime.cast(null, "int"))
        assertFailsWith<IllegalStateException> { castTo("x", "uuid") }
        assertFailsWith<IllegalStateException> { castTo("x", "int") }
    }

    @Test fun vectorCellsAcceptPlainArraysAndText() {
        assertContentEquals(floatArrayOf(1f, 2.5f, -3f), vectorOf("[1, 2.5, -3]"))
        assertContentEquals(floatArrayOf(), vectorOf("[]"))
        assertContentEquals(floatArrayOf(1f, 2f), vectorOf(doubleArrayOf(1.0, 2.0)))
        assertNull(vectorOf(null))
    }
}
