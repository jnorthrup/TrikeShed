package org.xvm.cursor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventSeriesTest {
    @Test
    fun `delegate capture pushes updated signature into batch`() {
        val meta = ColumnMetaRef(7, "payload", "String")
        val eventSeries = EventSeries(listOf("a").toSeries(), meta)
        val batch = BatchMutableSeries<String>()

        batch.attach(eventSeries)
        eventSeries.series = listOf("b", "c").toSeries()

        assertEquals(1, batch.a)
        val signature = batch.b(0)
        assertEquals(meta, signature.b)
        assertEquals(listOf("b", "c"), valuesOf(signature.a))
    }

    @Test
    fun `wireproto delivery contains complete join signature payload`() {
        val meta = ColumnMetaRef(3, "steps", "String")
        val eventSeries = EventSeries(listOf("step1", "step2").toSeries(), meta)
        val batch = BatchMutableSeries<String>()

        batch.attach(eventSeries)
        eventSeries.series = listOf("step1", "step2", "step3").toSeries()

        val wire = batch.drainToWireproto()
        val decoded = decode(wire)
        assertEquals(1, decoded.a)
        assertEquals(meta, decoded.b(0).b)
        assertEquals(listOf("step1", "step2", "step3"), valuesOf(decoded.b(0).a))
    }

    @Test
    fun `event delivery snapshots old and new series`() {
        val meta = ColumnMetaRef(9, "count", "Int")
        val eventSeries = EventSeries(listOf(1, 2).toSeries()) { meta }
        val batch = BatchMutableSeries<Int>()

        batch.attach(eventSeries)
        eventSeries.series = listOf(1, 2, 3).toSeries()
        eventSeries.series = listOf(4).toSeries()

        val deliveries = batch.deliveries()
        assertEquals(2, deliveries.a)
        assertEquals(listOf(1, 2, 3), valuesOf(deliveries.b(0).signature.a))
        assertEquals(listOf(4), valuesOf(deliveries.b(1).signature.a))
        assertTrue(deliveries.b(0).wireproto.isNotEmpty())
        assertTrue(deliveries.b(1).wireproto.isNotEmpty())
        assertArrayEquals(deliveries.b(1).wireproto, batch.singleWireproto(deliveries.b(1).signature))
    }

    private fun decode(bytes: ByteArray): Series<Join<Series<String>, ColumnMetaRef>> =
        WireSeries.strings().decode(bytes)

    private fun <T> valuesOf(series: Series<T>): List<T> = List(series.a) { index -> series.b(index) }
}
