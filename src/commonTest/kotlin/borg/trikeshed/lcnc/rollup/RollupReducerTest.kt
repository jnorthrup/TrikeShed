package borg.trikeshed.lcnc.rollup

import borg.trikeshed.lcnc.collections.associative.PropertyValue
import borg.trikeshed.lcnc.collections.associative.PropertyType
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lib.j
import borg.trikeshed.lib.emptySeriesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RollupReducerTest {

    private fun createDummyDb(vararg values: Double?): LcncDatabase {
        val pages = values.mapIndexed { index, value ->
            val content = value?.let { PropertyValue("prop1", PropertyType.NUMBER, it) }
            val block = LcncBlock(
                id = "block$index",
                type = "property_wrapper",
                parentId = "page$index",
                content = content
            )
            LcncPage(
                id = "page$index",
                title = "Page $index",
                parentId = "db1",
                contentBlocks = 1 j { block }
            )
        }
        return LcncDatabase(
            id = "db1",
            title = "Test DB",
            parentId = null,
            pages = pages.size j { pages[it] }
        )
    }

    @Test
    fun sumRollupAggregatesValues() {
        val db = createDummyDb(1.0, 2.0, 3.0, 4.0, 5.0)
        val ctx = RollupContext(db, "prop1")
        val spec = RollupSpec("prop1", RollupFunction.Sum)
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Sum, result.function)
        assertEquals(15.0, result.value)
        assertEquals(5, result.sampleSize)
    }

    @Test
    fun countRollupCountsPages() {
        val db = createDummyDb(1.0, null, 3.0, 4.0, 5.0)
        val ctx = RollupContext(db, "prop1")
        val spec = RollupSpec("prop1", RollupFunction.Count)
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Count, result.function)
        assertEquals(5.0, result.value)
        assertEquals(5, result.sampleSize)
    }

    @Test
    fun avgRollupComputesMean() {
        val db = createDummyDb(1.0, 2.0, 3.0, 4.0, 5.0)
        val ctx = RollupContext(db, "prop1")
        val spec = RollupSpec("prop1", RollupFunction.Avg)
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Avg, result.function)
        assertEquals(3.0, result.value)
        assertEquals(5, result.sampleSize)
    }

    @Test
    fun minRollupReturnsMin() {
        val db = createDummyDb(1.0, 2.0, 3.0, 4.0, 5.0)
        val ctx = RollupContext(db, "prop1")
        val spec = RollupSpec("prop1", RollupFunction.Min)
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Min, result.function)
        assertEquals(1.0, result.value)
        assertEquals(5, result.sampleSize)
    }

    @Test
    fun maxRollupReturnsMax() {
        val db = createDummyDb(1.0, 2.0, 3.0, 4.0, 5.0)
        val ctx = RollupContext(db, "prop1")
        val spec = RollupSpec("prop1", RollupFunction.Max)
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Max, result.function)
        assertEquals(5.0, result.value)
        assertEquals(5, result.sampleSize)
    }

    @Test
    fun emptyDatabaseReturnsNull() {
        val db = LcncDatabase(
            id = "db1",
            title = "Test DB",
            parentId = null,
            pages = emptySeriesOf()
        )
        val ctx = RollupContext(db, "prop1")
        val spec = RollupSpec("prop1", RollupFunction.Sum)
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertNull(result.value)
        assertEquals(0, result.sampleSize)
    }

    @Test
    fun percentileRollupReturnsQuantile() {
        val db = createDummyDb(*(1..100).map { it.toDouble() }.toTypedArray())
        val ctx = RollupContext(db, "prop1")
        val spec = RollupSpec("prop1", RollupFunction.Percentile(95.0))
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Percentile(95.0), result.function)
        assertEquals(95.0, result.value)
        assertEquals(100, result.sampleSize)
    }

    @Test
    fun stddevRollupComputesStdDev() {
        val db = createDummyDb(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        val ctx = RollupContext(db, "prop1")
        val spec = RollupSpec("prop1", RollupFunction.Stddev)
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Stddev, result.function)
        val value = result.value ?: 0.0
        val diff = kotlin.math.abs(value - 2.138)
        assert(diff < 0.01) { "Expected ~2.138, got $value" }
        assertEquals(8, result.sampleSize)
    }

    @Test
    fun decimalPlacesConfigRounds() {
        val db = createDummyDb(1.4, 1.4) // sum = 2.8
        val ctx = RollupContext(db, "prop1", RollupConfig(decimalPlaces = 0))
        val spec = RollupSpec("prop1", RollupFunction.Sum)
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(3.0, result.value)
    }

    @Test
    fun showEmptyGroupsConfigFlag() {
        val ctx = RollupContext(createDummyDb(), "prop1", RollupConfig(showEmptyGroups = true))
        val spec = RollupSpec("prop1", RollupFunction.Sum)
        val reducer = RollupReducer()

        assertEquals(true, ctx.config.showEmptyGroups)
    }
}
