package metrics

import fsm.FlywheelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the commonMain purge of [FlywheelMetrics]: the object must keep its
 * synchronous public surface and its counter / histogram / export semantics with
 * no JVM-only primitives behind it.
 */
class FlywheelMetricsTest {

    @Test
    fun resetClearsEveryCounter() {
        FlywheelMetrics.recordCycle(hadErrors = true)
        FlywheelMetrics.recordSlots(4)
        FlywheelMetrics.reset()

        assertEquals(0L, FlywheelMetrics.totalCycles)
        assertEquals(0L, FlywheelMetrics.cleanCycles)
        assertEquals(0L, FlywheelMetrics.errorCycles)
        assertEquals(0L, FlywheelMetrics.cyclesToday)
        assertEquals(0, FlywheelMetrics.activeSlots)
        assertEquals(0L, FlywheelMetrics.lastCycleMs)
        assertEquals("POLL", FlywheelMetrics.lastPhase)
        assertEquals(emptyList(), FlywheelMetrics.lastPhaseLatencies)
        assertEquals(0.0, FlywheelMetrics.cyclesPerSecond)
    }

    @Test
    fun cycleCountersSplitCleanAndError() {
        FlywheelMetrics.reset()
        FlywheelMetrics.recordCycle(hadErrors = false)
        FlywheelMetrics.recordCycle(hadErrors = false)
        FlywheelMetrics.recordCycle(hadErrors = true)

        assertEquals(3L, FlywheelMetrics.totalCycles)
        assertEquals(2L, FlywheelMetrics.cleanCycles)
        assertEquals(1L, FlywheelMetrics.errorCycles)
        assertEquals(3L, FlywheelMetrics.cyclesToday)
    }

    @Test
    fun slotsClampToTargetRange() {
        FlywheelMetrics.reset()
        FlywheelMetrics.recordSlots(-5)
        assertEquals(0, FlywheelMetrics.activeSlots)
        FlywheelMetrics.recordSlots(999)
        assertEquals(FlywheelMetrics.TARGET_SLOTS, FlywheelMetrics.activeSlots)
        FlywheelMetrics.recordSlots(7)
        assertEquals(7, FlywheelMetrics.activeSlots)
    }

    @Test
    fun phaseLatenciesLandInHistogramAndJson() {
        FlywheelMetrics.reset()
        FlywheelMetrics.recordPhaseLatencies(
            listOf(FlywheelMetrics.PhaseLatency("POLL", 30L), FlywheelMetrics.PhaseLatency("PLAN", 90L)),
            totalMs = 120L,
            phase = "PLAN",
        )

        assertEquals("PLAN", FlywheelMetrics.lastPhase)
        assertEquals(120L, FlywheelMetrics.lastCycleMs)
        assertEquals(2, FlywheelMetrics.lastPhaseLatencies.size)

        val json = FlywheelMetrics.toJsonMap()
        assertEquals(120L, json["lastCycleMs"])
        assertEquals(FlywheelMetrics.TARGET_SLOTS, json["targetSlots"])
        assertEquals(120L, json["histogramSumMs"])

        @Suppress("UNCHECKED_CAST")
        val counts = json["histogramCounts"] as List<Long>
        // buckets are 10,50,100,250,... — 120ms falls in the "<= 250" bucket (index 3)
        assertEquals(1L, counts[3])
        assertEquals(1L, counts.sum())
    }

    @Test
    fun transitionsAreCountedByName() {
        FlywheelMetrics.reset()
        FlywheelMetrics.recordTransition(FlywheelState.Idle, FlywheelState.Spinning)
        FlywheelMetrics.recordTransition(FlywheelState.Idle, FlywheelState.Spinning)
        FlywheelMetrics.recordTransition(FlywheelState.Spinning, FlywheelState.Fault)

        @Suppress("UNCHECKED_CAST")
        val transitions = FlywheelMetrics.toJsonMap()["transitions"] as Map<String, Long>
        assertEquals(2L, transitions["Idle->Spinning"])
        assertEquals(1L, transitions["Spinning->Fault"])
    }

    @Test
    fun prometheusExportIsWellFormed() {
        FlywheelMetrics.reset()
        FlywheelMetrics.recordCycle(hadErrors = false)
        FlywheelMetrics.recordPhaseLatencies(
            listOf(FlywheelMetrics.PhaseLatency("POLL", 5L)),
            totalMs = 5L,
            phase = "POLL",
        )
        FlywheelMetrics.recordSlots(3)

        val text = FlywheelMetrics.toPrometheusFormat()
        assertTrue("flywheel_total_cycles_total 1" in text, text)
        assertTrue("flywheel_slots_active 3" in text, text)
        assertTrue("flywheel_phase_current{phase=\"POLL\"} 1" in text, text)
        assertTrue("flywheel_phase_latency_ms{phase=\"POLL\"} 5" in text, text)
        assertTrue("flywheel_cycle_latency_seconds_cycle_bucket{le=\"10\"} 1" in text, text)
        assertTrue("flywheel_cycle_latency_seconds_cycle_sum 5" in text, text)
        // the one floating-point sample must be a plain decimal — never scientific notation
        val rate = text.lines().single { it.startsWith("flywheel_cycles_per_second ") }
            .removePrefix("flywheel_cycles_per_second ")
        assertTrue(Regex("""-?\d+\.\d{6}""").matches(rate), rate)
    }

    @Test
    fun histogramBucketsAreCumulativeAndMonotonic() {
        FlywheelMetrics.reset()
        val one = listOf(FlywheelMetrics.PhaseLatency("POLL", 1L))
        FlywheelMetrics.recordPhaseLatencies(one, totalMs = 5L, phase = "POLL")
        FlywheelMetrics.recordPhaseLatencies(one, totalMs = 120L, phase = "POLL")

        val counts = FlywheelMetrics.toPrometheusFormat().lines()
            .filter { it.startsWith("flywheel_cycle_latency_seconds_cycle_bucket") }
            .map { it.substringAfterLast(' ').toLong() }

        assertEquals(FlywheelMetrics.toJsonMap().let { (it["histogramBuckets"] as List<*>).size } + 1, counts.size)
        assertEquals(counts.sorted(), counts, "cumulative buckets must be non-decreasing: $counts")
        assertEquals(listOf(1L, 1L, 1L, 2L), counts.take(4)) // le=10, 50, 100, 250
        assertEquals(2L, counts.last()) // le=+Inf
        assertTrue("flywheel_cycle_latency_seconds_cycle_count 2" in FlywheelMetrics.toPrometheusFormat())
        assertTrue("flywheel_cycle_latency_seconds_cycle_sum 125" in FlywheelMetrics.toPrometheusFormat())
    }

    @Test
    fun dayWindowIsSevenSlotsWithTodayFirst() {
        FlywheelMetrics.reset()
        FlywheelMetrics.recordCycle(hadErrors = false)
        FlywheelMetrics.recordCycle(hadErrors = false)
        FlywheelMetrics.recordPhaseLatencies(emptyList(), totalMs = 1L, phase = "POLL")

        @Suppress("UNCHECKED_CAST")
        val perDay = FlywheelMetrics.toJsonMap()["cyclesPerDay7"] as List<Long>
        assertEquals(7, perDay.size)
        assertEquals(2L, perDay[0])
        assertEquals(List(6) { 0L }, perDay.drop(1))
    }

    @Test
    fun fixed6FormatsPlainDecimals() {
        assertEquals("0.000000", FlywheelMetrics.fixed6(0.0))
        assertEquals("1.000000", FlywheelMetrics.fixed6(1.0))
        assertEquals("0.000010", FlywheelMetrics.fixed6(1e-5))
        assertEquals("0.001157", FlywheelMetrics.fixed6(100.0 / 86_400.0))
        assertEquals("12.345679", FlywheelMetrics.fixed6(12.3456789))
        assertEquals("-2.500000", FlywheelMetrics.fixed6(-2.5))
        assertEquals("0.000000", FlywheelMetrics.fixed6(-1e-9))
    }
}
