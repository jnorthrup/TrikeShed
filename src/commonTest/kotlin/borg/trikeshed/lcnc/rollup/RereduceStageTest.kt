package borg.trikeshed.lcnc.rollup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RereduceStageTest {

    @Test
    fun defaultStagePreservesCarrier() {
        val stage = DefaultRereduceStage()
        val carrier = "test_carrier"
        // Dummy Context
        val dummyDb = borg.trikeshed.lcnc.isam.LcncDatabase(
            id = "db1",
            title = "Title",
            parentId = null,
            pages = borg.trikeshed.lib.emptySeriesOf()
        )
        val ctx = RollupContext(dummyDb, "prop1")

        val result = stage.apply(carrier, ctx)
        assertEquals(carrier, result)
    }

    @Test
    fun rereduceStageImplementsInterface() {
        val stage: RereduceStage = DefaultRereduceStage()
        assertTrue(stage is RereduceStage)
    }

    @Test
    fun rollupSpecRejectsBlankProperty() {
        assertFailsWith<IllegalArgumentException> {
            RollupSpec("", RollupFunction.Sum)
        }
        assertFailsWith<IllegalArgumentException> {
            RollupSpec("  ", RollupFunction.Sum)
        }
    }

    @Test
    fun rollupFunctionFromNameRecognisesKnown() {
        assertEquals(RollupFunction.Sum, RollupFunction.fromName("sum"))
        assertEquals(RollupFunction.Percentile(50.0), RollupFunction.fromName("percentile_50.0"))
        assertEquals(RollupFunction.Percentile(95.0), RollupFunction.fromName("percentile_95.0"))
        assertEquals(RollupFunction.Count, RollupFunction.fromName("count"))
        assertEquals(RollupFunction.Min, RollupFunction.fromName("min"))
        assertEquals(RollupFunction.Max, RollupFunction.fromName("max"))
        assertEquals(RollupFunction.Avg, RollupFunction.fromName("avg"))
        assertEquals(RollupFunction.Stddev, RollupFunction.fromName("stddev"))
    }

    @Test
    fun rollupFunctionFromNameRejectsUnknown() {
        assertNull(RollupFunction.fromName("unknown"))
        assertNull(RollupFunction.fromName("sum_unknown"))
    }
}
