package org.xvm.cursor

import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

typealias TypedefFactSeries = Series<TypedefFact>

/**
 * Cold TrikeShed PRELOAD.md path:
 * Series<T> = Join<Int, (Int) -> T>, lazy α projection, and .view at the boundary.
 * No SharedFlow, Channel, or eager collection map/filter path is needed here.
 */
class ColdAlphaConversionSeriesViewTest {

    @BeforeEach
    fun reset() {
        StringPool.clear()
        TypedefResolutionSeries.reset()
    }

    @Test
    fun `series view is the cold boundary for captured typedef facts`() {
        val poolId = StringPool.intern("ColdViewPool")
        for (i in 0 until 32) {
            TypedefResolutionSeries.record(poolId, i, "pkg.Cold$i", "fmt$i", i % 2 == 0)
        }

        val facts: TypedefFactSeries = TypedefResolutionSeries.snapshotEvents()
        var count = 0
        for (fact in facts.view) {
            assertTrue(fact.clsName.startsWith("pkg.Cold"))
            count++
        }

        assertEquals(32, count)
    }

    @Test
    fun `alpha conversion projects fact ids without hot collection map`() {
        val poolId = StringPool.intern("AlphaProjectionPool")
        for (i in 0 until 8) {
            TypedefResolutionSeries.record(poolId, i, "pkg.Alpha$i", "fmt$i", true)
        }

        val ids = TypedefResolutionSeries.snapshotEvents().α { fact: TypedefFact -> fact.factId }
        var sum = 0L
        for (id in ids.view) {
            sum += id
        }

        assertEquals(28L, sum)
    }

    @Test
    fun `cold reducer journal is not a hot flow`() {
        val journal = TypedefResolutionSeries.reduxJournal()
        val className = journal::class.java.name

        assertTrue(!className.contains("SharedFlow"), "journal must not be SharedFlow")
        assertTrue(!className.contains("Channel"), "journal must not be Channel")
        assertTrue(className.contains("ReduxMutableSeries"), "journal must be ReduxMutableSeries")
    }

    @Test
    fun `chunked series view preserves insertion order`() {
        val series = ChunkedMutableSeries<TypedefFact>(chunkSize = 16)
        val poolId = StringPool.intern("OrderPool")
        for (i in 0 until 16) {
            series.add(TypedefFact(i.toLong(), i.toLong(), poolId, i, "pkg.Order$i", "fmt$i", true))
        }

        var expected = 0
        for (fact in series.view) {
            assertEquals(expected, fact.siteOrd)
            expected++
        }
        assertEquals(16, expected)
    }
}
