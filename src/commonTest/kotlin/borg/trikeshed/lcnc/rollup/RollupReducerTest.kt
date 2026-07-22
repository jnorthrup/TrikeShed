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

    private fun createDummyDbWithGroups(vararg entries: Pair<String, Double>): LcncDatabase {
        val pages = entries.mapIndexed { index, entry ->
            val groupContent = PropertyValue("groupProp", PropertyType.TEXT, entry.first)
            val valContent = PropertyValue("valProp", PropertyType.NUMBER, entry.second)
            
            val groupBlock = LcncBlock(id = "gb$index", type = "property_wrapper", parentId = "page$index", content = groupContent)
            val valBlock = LcncBlock(id = "vb$index", type = "property_wrapper", parentId = "page$index", content = valContent)

            LcncPage(
                id = "page$index",
                title = "Page $index",
                parentId = "db1",
                contentBlocks = 2 j { if(it == 0) groupBlock else valBlock }
            )
        }
        return LcncDatabase(
            id = "db1",
            title = "Test DB Grouped",
            parentId = null,
            pages = pages.size j { pages[it] }
        )
    }

    @Test
    fun sumRollupAggregatesValuesByGroup() {
        val db = createDummyDbWithGroups("A" to 10.0, "B" to 20.0, "A" to 30.0)
        val ctx = RollupContext(db, "valProp")
        val spec = RollupSpec("valProp", RollupFunction.Sum, groupByPropertyId = "groupProp")
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Sum, result.function)
        assertEquals(60.0, result.value)
        assertEquals(3, result.sampleSize)

        val groups = result.groups
        kotlin.test.assertNotNull(groups)
        assertEquals(2, groups.size)
        
        val groupA = groups["A"]
        kotlin.test.assertNotNull(groupA)
        assertEquals(40.0, groupA.value)
        assertEquals(2, groupA.sampleSize)

        val groupB = groups["B"]
        kotlin.test.assertNotNull(groupB)
        assertEquals(20.0, groupB.value)
        assertEquals(1, groupB.sampleSize)
    }

    @Test
    fun avgRollupAggregatesValuesByGroup() {
        val db = createDummyDbWithGroups("A" to 10.0, "B" to 20.0, "A" to 30.0)
        val ctx = RollupContext(db, "valProp")
        val spec = RollupSpec("valProp", RollupFunction.Avg, groupByPropertyId = "groupProp")
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Avg, result.function)
        assertEquals(20.0, result.value)
        assertEquals(3, result.sampleSize)

        val groups = result.groups
        kotlin.test.assertNotNull(groups)
        assertEquals(2, groups.size)
        
        val groupA = groups["A"]
        kotlin.test.assertNotNull(groupA)
        assertEquals(20.0, groupA.value)
        assertEquals(2, groupA.sampleSize)

        val groupB = groups["B"]
        kotlin.test.assertNotNull(groupB)
        assertEquals(20.0, groupB.value)
        assertEquals(1, groupB.sampleSize)
    }
    
    @Test
    fun countRollupAggregatesValuesByGroup() {
        val db = createDummyDbWithGroups("A" to 10.0, "B" to 20.0, "A" to 30.0)
        val ctx = RollupContext(db, "valProp")
        val spec = RollupSpec("valProp", RollupFunction.Count, groupByPropertyId = "groupProp")
        val reducer = RollupReducer()
        val result = reducer.reduce(ctx, spec)

        assertEquals(RollupFunction.Count, result.function)
        assertEquals(3.0, result.value)
        assertEquals(3, result.sampleSize)

        val groups = result.groups
        kotlin.test.assertNotNull(groups)
        assertEquals(2, groups.size)
        
        val groupA = groups["A"]
        kotlin.test.assertNotNull(groupA)
        assertEquals(2.0, groupA.value)
        assertEquals(2, groupA.sampleSize)

        val groupB = groups["B"]
        kotlin.test.assertNotNull(groupB)
        assertEquals(1.0, groupB.value)
        assertEquals(1, groupB.sampleSize)
    }
}
