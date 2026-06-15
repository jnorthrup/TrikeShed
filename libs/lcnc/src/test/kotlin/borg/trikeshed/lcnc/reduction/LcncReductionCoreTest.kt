package borg.trikeshed.lcnc.reduction

import borg.trikeshed.lib.*
import kotlin.test.*
import kotlin.random.Random

class LcncReductionCoreTest {

    // ── KeyAlg Tests ──────────────────────────────────────────────

    @Test
    fun testForgeKeyHierarchy() {
        val hierarchy = LcncKeyAlg.forgeKeyHierarchy(listOf("region", "product"))
        val input = mapOf("region" to "US", "product" to "Widget", "other" to "ignored")

        val composite = hierarchy.compositeKey(input)
        assertEquals(listOf("US", "Widget"), composite)

        val prefix = hierarchy.prefix(composite, 1)
        assertEquals(listOf("US"), prefix)
    }

    @Test
    fun testConfixStructuralKey() {
        val hierarchy = LcncKeyAlg.confixStructuralKey()
        val event = SpanEvent(2, SpanEvent.Span(10, 20))

        val composite = hierarchy.compositeKey(event)
        assertEquals(1, composite.size)
        assertEquals(2, composite[0].depth)
        assertEquals(10, composite[0].open)
        assertEquals(20, composite[0].close)
    }

    @Test
    fun testCrmsCallsiteHash() {
        val extractor = LcncKeyAlg.crmsCallsiteHash()
        val event1 = TraceEvent(0xA5, 10, 20)
        val event2 = TraceEvent(0xA5, 10, 20)
        val event3 = TraceEvent(0xA6, 10, 20)

        // Same input → same hash
        assertEquals(extractor.extract(event1), extractor.extract(event2))
        // Different opcode → different hash
        assertNotEquals(extractor.extract(event1), extractor.extract(event3))

        // 32-bit hash
        val hash = extractor.extract(event1)
        assertTrue(hash >= Int.MIN_VALUE && hash <= Int.MAX_VALUE)
    }

    @Test
    fun testNaturalKeyOrder() {
        val order = LcncKeyAlg.naturalKeyOrder<Int>()
        assertEquals(-1, order.compare(1, 2))
        assertEquals(0, order.compare(5, 5))
        assertEquals(1, order.compare(10, 5))
        assertTrue(order.equiv(7, 7))
        assertFalse(order.equiv(7, 8))
    }

    // ── ValueAlg Tests ────────────────────────────────────────────

    @Test
    fun testFolderAssociativity() {
        val folder = ForgeReducers.sumReducer("value")
        val initial = MultiMetricAccumulator()

        // fold(fold(acc, a), b) == fold(acc, a + b) for associative operations
        val input1 = mapOf("value" to 10.0)
        val input2 = mapOf("value" to 20.0)

        val acc1 = folder.fold(folder.fold(initial, input1), input2)
        val acc2 = folder.fold(initial, mapOf("value" to 30.0))

        assertEquals(acc1.sums["value"], acc2.sums["value"])
    }

    @Test
    fun testMergerCommutativity() {
        val merger = ForgeReducers.forgeMerger

        val acc1 = MultiMetricAccumulator(sums = mapOf("a" to 10.0))
        val acc2 = MultiMetricAccumulator(sums = mapOf("a" to 20.0))
        val acc3 = MultiMetricAccumulator(sums = mapOf("a" to 30.0))

        val partials1 = s_[acc1, acc2, acc3]
        val partials2 = s_[acc3, acc1, acc2]

        val merged1 = merger.merge(partials1)
        val merged2 = merger.merge(partials2)

        assertEquals(merged1.sums["a"], merged2.sums["a"])
    }

    @Test
    fun testMultiMetricAccumulator() {
        val folder = ForgeReducers.buildMultiMetricFolder(listOf(
            "sales" to BuiltinReducer.SUM,
            "count" to BuiltinReducer.COUNT,
            "price" to BuiltinReducer.MIN,
            "price" to BuiltinReducer.MAX
        ))

        val acc = folder.fold(MultiMetricAccumulator(), mapOf(
            "sales" to 100.0,
            "count" to 1,
            "price" to 50.0
        ))

        assertEquals(100.0, acc.sums["sales"])
        assertEquals(1, acc.counts["count"])
        assertEquals(50.0, acc.mins["price"])
        assertEquals(50.0, acc.maxs["price"])
    }

    @Test
    fun testConfixTreeBuilder() {
        val folder = LcncValueAlg.confixTreeBuilder()
        val initial = TreeBuilderState()

        val span1 = SpanEvent(0, SpanEvent.Span(0, 10))
        val span2 = SpanEvent(1, SpanEvent.Span(2, 8))
        val span3 = SpanEvent(0, SpanEvent.Span(12, 20))

        val state1 = folder.fold(initial, span1)
        val state2 = folder.fold(state1, span2)
        val state3 = folder.fold(state2, span3)

        // span2 is nested in span1, span3 is sibling of span1
        assertEquals(2, state3.roots.size)  // span1 (with span2 child) and span3
        assertEquals(1, state3.roots[0].children.size)  // span2 is child of span1
    }

    @Test
    fun testCrmsPairAndEigsort() {
        val folder = LcncValueAlg.crmsPairAndEigsort()
        val initial = ConflictCell.init()

        val before = TraceEvent(0xA5, 5, 100)  // L_GET
        val after = TraceEvent(0xA6, 5, 100)   // L_SET
        val anotherBefore = TraceEvent(0xA7, 3, 100)  // P_GET

        val acc1 = folder.fold(initial, before)
        val acc2 = folder.fold(acc1, after)
        val acc3 = folder.fold(acc2, anotherBefore)

        assertEquals(2, acc3.beforeEvents.size)
        assertEquals(1, acc3.afterEvents.size)
        assertEquals(3, acc3.frequency)
    }

    @Test
    fun testCrmsMerger() {
        val merger = LcncValueAlg.crmsMerger()

        val cell1 = ConflictCell(
            callsiteHash = 100,
            beforeEvents = listOf(TraceEvent(0xA5, 5, 100)),
            afterEvents = emptyList(),
            depth = 5,
            frequency = 1
        )
        val cell2 = ConflictCell(
            callsiteHash = 100,
            beforeEvents = emptyList(),
            afterEvents = listOf(TraceEvent(0xA6, 5, 100)),
            depth = 5,
            frequency = 1
        )

        val partials = s_[cell1, cell2]
        val merged = merger.merge(partials)

        assertEquals(100, merged.callsiteHash)
        assertEquals(1, merged.beforeEvents.size)
        assertEquals(1, merged.afterEvents.size)
        assertEquals(2, merged.frequency)
    }

    // ── PhaseAlg Tests ────────────────────────────────────────────

    @Test
    fun testForgePhaseTransitions() {
        val alg = LcncPhaseAlg.forgePhaseAlg

        // Valid sequence: MAP → REDUCE → REREDUCE → FILTER
        val validSeq = listOf(
            ReductionPhase.MAP,
            ReductionPhase.REDUCE,
            ReductionPhase.REREDUCE,
            LcncPhaseAlg.FILTER
        )
        assertTrue(alg.validateSequence(validSeq))

        // Invalid: REDUCE before MAP
        val invalidSeq = listOf(ReductionPhase.REDUCE, ReductionPhase.MAP)
        assertFalse(alg.validateSequence(invalidSeq))

        // Invalid: MAP → MAP
        val invalidSeq2 = listOf(ReductionPhase.MAP, ReductionPhase.MAP)
        assertFalse(alg.validateSequence(invalidSeq2))
    }

    @Test
    fun testConfixPhaseTransitions() {
        val alg = LcncPhaseAlg.confixPhaseAlg

        // Valid: MAP → REDUCE
        assertTrue(alg.validateSequence(listOf(ReductionPhase.MAP, ReductionPhase.REDUCE)))

        // Invalid: REDUCE → MAP
        assertFalse(alg.validateSequence(listOf(ReductionPhase.REDUCE, ReductionPhase.MAP)))
    }

    @Test
    fun testCrmsPhaseTransitions() {
        val alg = LcncPhaseAlg.crmsPhaseAlg

        // Valid: BEFORE → AFTER
        assertTrue(alg.validateSequence(listOf(ReductionPhase.BEFORE, ReductionPhase.AFTER)))

        // Invalid: AFTER → BEFORE
        assertFalse(alg.validateSequence(listOf(ReductionPhase.AFTER, ReductionPhase.BEFORE)))
    }

    // ── CarrierAlg Tests ──────────────────────────────────────────

    @Test
    fun testSeriesCarrier() {
        val series: Series<Int> = 10 j { i -> i * 2 }
        val carrier = LcncCarrierAlg.seriesCarrier(series)

        assertEquals(10, carrier.size)
        assertEquals(0, carrier.get(0))
        assertEquals(18, carrier.get(9))

        val mapped = carrier.map { it + 1 }
        assertEquals(1, mapped.get(0))
        assertEquals(19, mapped.get(9))

        val filtered = carrier.filter { it % 4 == 0 }
        assertEquals(5, filtered.size)  // 0, 4, 8, 12, 16

        val folded = carrier.fold(0) { acc, v -> acc + v }
        assertEquals(90, folded)  // sum of 0+2+4+...+18
    }

    @Test
    fun testArrayCarrier() {
        val arr = arrayOf(1, 2, 3, 4, 5)
        val carrier = LcncCarrierAlg.arrayCarrier(arr)

        assertEquals(5, carrier.size)
        assertEquals(3, carrier.get(2))

        val sorted = carrier.sortBy { it }
        assertEquals(1, sorted.get(0))
        assertEquals(5, sorted.get(4))
    }

    @Test
    fun testGroupBy() {
        val series: Series<Pair<String, Int>> = 6 j { i ->
            Pair(if (i % 2 == 0) "even" else "odd", i)
        }
        val carrier = LcncCarrierAlg.seriesCarrier(series)

        val grouped = carrier.groupBy { it.first }
        assertEquals(2, grouped.size)
        assertEquals(3, grouped["even"]!!.size)
        assertEquals(3, grouped["odd"]!!.size)
    }

    // ── LcncReduction Integration Tests ───────────────────────────

    @Test
    fun testForgeCascadeReduction() {
        val reduction = LcncReductions.forgeCascade(
            keyHierarchy = listOf("region", "product"),
            metrics = listOf("sales" to BuiltinReducer.SUM, "count" to BuiltinReducer.COUNT)
        )

        val inputData = listOf(
            mapOf("region" to "US", "product" to "A", "sales" to 100.0, "count" to 1),
            mapOf("region" to "US", "product" to "A", "sales" to 200.0, "count" to 1),
            mapOf("region" to "EU", "product" to "B", "sales" to 150.0, "count" to 1)
        )
        val carrier = LcncCarrierAlg.seriesCarrier(inputData.size j { i -> inputData[i] })

        val result = reduction.execute(carrier)

        assertEquals(2, result.size)  // US-A and EU-B
        val usA = result.find { it.key == listOf("US", "A") }!!
        assertEquals(300.0, usA.accumulator.sums["sales"])
        assertEquals(2, usA.accumulator.counts["count"])
    }

    @Test
    fun testCrmsFoldReduction() {
        val reduction = LcncReductions.crmsFold()

        val events = arrayOf(
            TraceEvent(0xA5, 5, 100),  // BEFORE
            TraceEvent(0xA6, 5, 100),  // AFTER
            TraceEvent(0xA5, 3, 101),  // BEFORE
            TraceEvent(0xA6, 3, 101)   // AFTER
        )
        val carrier = LcncCarrierAlg.arrayCarrier(events)

        val result = reduction.execute(carrier)

        assertEquals(2, result.size)  // Two callsites
        assertTrue(result.all { it.beforeEvents.size == 1 && it.afterEvents.size == 1 })
        // Sorted by depth desc
        assertTrue(result[0].depth >= result[1].depth)
    }

    @Test
    fun testExecuteWithCheckpoints() {
        val reduction = LcncReductions.forgeCascade(
            keyHierarchy = listOf("key"),
            metrics = listOf("value" to BuiltinReducer.SUM)
        )

        val inputData = listOf(
            mapOf("key" to "A", "value" to 10.0),
            mapOf("key" to "A", "value" to 20.0)
        )
        val carrier = LcncCarrierAlg.seriesCarrier(inputData.size j { i -> inputData[i] })

        val result = reduction.executeWithCheckpoints(carrier)

        assertNotNull(result.output)
        assertTrue(result.stageOutputs.containsKey(ReductionPhase.MAP))
        assertTrue(result.stageOutputs.containsKey(ReductionPhase.REDUCE))
    }

    // ── Property-based Tests ──────────────────────────────────────

    @Test
    fun testGroupByFoldEquivalence() {
        // Property: groupBy(key).fold() == fold() over grouped for commutative folders
        val folder = ForgeReducers.sumReducer("value")
        val initial = MultiMetricAccumulator()

        for (seed in 0..9) {
            Random(seed.toLong()).nextInts(100).forEach { size ->
                val data = (0 until size).map { _ ->
                    mapOf("key" to Random.nextInt(10).toString(), "value" to Random.nextDouble() * 100)
                }
                val carrier = LcncCarrierAlg.seriesCarrier(data.size j { i -> data[i] })

                // Direct fold
                val direct = carrier.fold(initial) { acc, v -> folder.fold(acc, v) }

                // Group by key then fold
                val grouped = carrier.groupBy { (it as Map<String, Any>)["key"] as String }
                var groupedResult = initial
                for ((_, groupCarrier) in grouped) {
                    groupedResult = groupCarrier.fold(groupedResult) { acc, v -> folder.fold(acc, v) }
                }

                assertEquals(direct.sums.values.sum(), groupedResult.sums.values.sum())
            }
        }
    }

    @Test
    fun testMergerAssociativity() {
        // Property: merger.merge(partials) is associative
        val merger = ForgeReducers.forgeMerger

        for (seed in 0..9) {
            Random(seed.toLong()).nextInts(50).forEach { _ ->
                val partials = (0 until 5).map { _ ->
                    MultiMetricAccumulator(sums = mapOf("x" to Random.nextDouble() * 100))
                }
                val series = partials.size j { i -> partials[i] }

                // Merge all at once
                val merged1 = merger.merge(series)

                // Merge pairwise then merge results
                val merged2 = merger.merge(
                    s_[merger.merge(s_[partials[0], partials[1]]),
                       merger.merge(s_[partials[2], partials[3]]),
                       partials[4]]
                )

                assertEquals(merged1.sums["x"]!!, merged2.sums["x"]!!, 0.0001)
            }
        }
    }
}