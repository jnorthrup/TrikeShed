package org.xvm.cursor

import borg.trikeshed.lib.toSeries
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringHashFacetCursorTest {
    @Test
    fun `source cursor points at a single child hash blackboard`() {
        val pool = StringHashFacetCursor(listOf("duck", "goose", "duck").toSeries())

        val row = pool.at(0)
        val valueMeta = row.b(0).b()
        val child = valueMeta.child

        assertEquals("value", valueMeta.name)
        assertNotNull(child)
        assertEquals("codecHash", child!!.name)
        assertSame(pool.hashBlackboard(), pool.childCursor())
    }

    @Test
    fun `hash blackboard collapses repeated strings into one facet row per pool id`() {
        val pool = StringHashFacetCursor(listOf("duck", "goose", "duck", "duck", "swan").toSeries())

        val blackboard = pool.hashBlackboard()
        assertEquals(3, blackboard.a)

        val duck = blackboard.b(0)
        val codecHash = duck.b(0).a as Long
        val poolId = duck.b(1).a as Int

        assertEquals("duck", duck.b(2).a)
        assertEquals(3, duck.b(3).a)
        assertEquals(codecHash, pool.codecHashes().b(0))
        assertEquals(codecHash, pool.codecHashes().b(2))
        assertEquals(codecHash, pool.codecHashes().b(3))
        assertEquals(poolId, pool.hashIds().b(0))
        assertEquals(poolId, pool.hashIds().b(2))
        assertEquals(poolId, pool.hashIds().b(3))
    }

    @Test
    fun `hash facet row carries lazy wireproto io`() {
        val pool = StringHashFacetCursor(listOf("duck", "goose", "duck", "duck").toSeries())

        val duck = pool.hashBlackboard().b(0)
        val codecHash = duck.b(0).a as Long
        val poolId = duck.b(1).a as Int
        val wire = duck.b(4).a as MemSegment
        val record = wire.reifier()

        assertEquals(codecHash, record.long)
        assertEquals(poolId, record.int)
        assertTrue(record.int > 0)
    }

    @Test
    fun `rows facet carries occurrence cursor for each stable hash`() {
        val pool = StringHashFacetCursor(listOf("duck", "goose", "duck", "duck").toSeries())

        val duck = pool.hashBlackboard().b(0)
        val rows = duck.b(5).a as Cursor

        assertEquals(3, rows.a)
        assertEquals(0, rows.b(0).b(0).a)
        assertEquals(2, rows.b(1).b(0).a)
        assertEquals(3, rows.b(2).b(0).a)
        assertEquals("duck", rows.b(0).b(3).a)
        assertEquals("duck", rows.b(1).b(3).a)
        assertEquals("duck", rows.b(2).b(3).a)
    }

    @Test
    fun `blackboard cursor is cached and codec is deterministic`() {
        val pool = StringHashFacetCursor(listOf("duck", "duck").toSeries())

        assertSame(pool.hashBlackboard(), pool.hashBlackboard())
        assertEquals(pool.codec.hash("duck"), pool.codec.hash("duck"))
        assertTrue(pool.codec.hash("duck") != pool.codec.hash("goose"))
    }

    @Test
    fun `alternate hash tables stay lazy and support pool id and codec hash lookups`() {
        val pool = StringHashFacetCursor(listOf("duck", "goose", "duck").toSeries())

        assertTrue(!pool.alternateHashTablesLoaded())

        val poolId = pool.hashIds().b(0)
        val codecHash = pool.codecHashes().b(0)
        val poolFacet = pool.facetByPoolId(poolId)
        val codecCursor = pool.facetsByCodecHash(codecHash)

        assertEquals("duck", poolFacet!!.b(2).a)
        assertEquals(1, codecCursor.a)
        assertEquals("duck", codecCursor.b(0).b(2).a)
        assertTrue(pool.alternateHashTablesLoaded())
    }
}
