package metrics

import borg.trikeshed.isam.synchronizedLock
import fsm.FlywheelState
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.datetime.Clock

/**
 * Flywheel telemetry — in-memory metrics with JSON and Prometheus text-format export.
 *
 * Thread-safe via [synchronizedLock] on all mutating operations (the project's
 * expect/actual monitor: a real monitor on the JVM, a no-op on the JS/Wasm actuals;
 * the Native actual is also a no-op, so Native callers must stay single-threaded).
 * Cross-lock reads are published through the multiplatform
 * [kotlin.concurrent.Volatile] annotation.
 *
 * Pure commonMain: no `java.*`, no `System.*`, no JVM `synchronized`. Wall clock
 * comes from [kotlinx.datetime.Clock]; the daemon writes this to the UNIX health
 * socket on every connection and exports it via /metrics.
 */
object FlywheelMetrics {

    private const val MS_PER_DAY = 86_400_000L

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    // ── State machine transitions ────────────────────────────────────────────────

    private val _transitionCounts = mutableMapOf<String, Long>()
    private val _transitionLock = Any()

    fun recordTransition(from: FlywheelState, to: FlywheelState) {
        synchronizedLock(_transitionLock) {
            val key = "${from::class.simpleName}->${to::class.simpleName}"
            _transitionCounts[key] = (_transitionCounts[key] ?: 0L) + 1L
        }
    }

    // ── Cycle counters ───────────────────────────────────────────────────────────

    /** Total cycles completed since daemon start. */
    @Volatile
    var totalCycles: Long = 0L
        private set

    /** Cycles that completed with zero errors. */
    @Volatile
    var cleanCycles: Long = 0L
        private set

    /** Cycles that had at least one error. */
    @Volatile
    var errorCycles: Long = 0L
        private set

    /** Cycles completed today (midnight-UTC reset). */
    @Volatile
    var cyclesToday: Long = 0L
        private set

    private var _cyclesTodayResetMs: Long = currentDayStartMs()
    private val _cycleLock = Any()

    /**
     * Epoch millis of the most recent midnight UTC. Unix time has no leap seconds,
     * so flooring to the day boundary is exactly `atStartOfDay(UTC)`.
     */
    private fun currentDayStartMs(): Long = (nowMs() / MS_PER_DAY) * MS_PER_DAY

    /** Call once per completed cycle from CycleBody.run() */
    fun recordCycle(hadErrors: Boolean) {
        synchronizedLock(_cycleLock) {
            val nowMs = nowMs()
            if (nowMs >= _cyclesTodayResetMs + MS_PER_DAY) {
                // Midnight UTC passed — bank the finished day, then reset the daily counter
                rollDayWindow(cyclesToday)
                cyclesToday = 0L
                _cyclesTodayResetMs = currentDayStartMs()
            }
            _lastCycleTimestampMs = nowMs
            totalCycles++
            cyclesToday++
            if (!hadErrors) cleanCycles++ else errorCycles++
        }
    }

    // ── Phase latency tracking ───────────────────────────────────────────────────

    /**
     * Per-phase milliseconds recorded during the last cycle.
     * Populated by FlywheelDriver.cycle() and read by CycleBody.run().
     */
    data class PhaseLatency(
        val phase: String,
        val ms: Long,
    )

    @Volatile
    var lastPhaseLatencies: List<PhaseLatency> = emptyList()
        private set

    @Volatile
    var lastPhase: String = "POLL"
        private set

    @Volatile
    var lastCycleMs: Long = 0L
        private set

    /**
     * Rolling window: cycles-per-day for the last 7 days (index 0 = today).
     * Slot 0 tracks the running total for the current UTC day; the midnight
     * rollover in [recordCycle] shifts the completed day into slot 1.
     */
    private val _cyclesPerDayRing = LongArray(7)
    private val _ringLock = Any()

    /**
     * Shift the day window one slot to the right, dropping the oldest day.
     * Called from [recordCycle] while `_cycleLock` is held — `_cycleLock` → `_ringLock`
     * is the only lock nesting in this object, and never occurs in the other order.
     */
    private fun rollDayWindow(finishedDayTotal: Long) = synchronizedLock(_ringLock) {
        for (i in _cyclesPerDayRing.lastIndex downTo 2) {
            _cyclesPerDayRing[i] = _cyclesPerDayRing[i - 1]
        }
        _cyclesPerDayRing[1] = finishedDayTotal
        _cyclesPerDayRing[0] = 0L
    }

    // ── Phase latency histograms ──────────────────────────────────────────────────

    /**
     * Exponential histogram buckets for cycle latency (ms).
     * Buckets: 10, 50, 100, 250, 500, 1000, 2500, 5000, 10000 ms.
     */
    private val _latencyBuckets = longArrayOf(10, 50, 100, 250, 500, 1000, 2500, 5000, 10000)
    private val _histogramCounters = LongArray(_latencyBuckets.size + 1) // +Inf bucket
    private var _histogramSum = 0L
    private val _histogramLock = Any()

    /** Consistent (counts, sum) snapshot of the latency histogram. */
    private fun histogramSnapshot(): Pair<LongArray, Long> =
        synchronizedLock(_histogramLock) { _histogramCounters.copyOf() to _histogramSum }

    /** Timestamp (epoch ms) of the most recent cycle start. */
    @Volatile
    private var _lastCycleTimestampMs: Long = 0L

    /**
     * Cycles per second — instantaneous rate based on interval between the
     * last two completed cycles. Zero until at least two cycles have run.
     */
    val cyclesPerSecond: Double
        get() = synchronizedLock(_cycleLock) {
            if (_lastCycleTimestampMs == 0L || totalCycles < 2L) return@synchronizedLock 0.0
            val elapsed = nowMs() - _lastCycleTimestampMs
            if (elapsed > 0) 1_000.0 / elapsed else 0.0
        }

    /**
     * True when cycles-per-day rate is at or above 100/day.
     * Compared at midnight-UTC boundary — cyclesToday / seconds-today.
     */
    val cyclesAt100PerDay: Boolean
        get() = synchronizedLock(_cycleLock) {
            val msToday = nowMs() - _cyclesTodayResetMs
            val secondsToday = (msToday / 1_000.0).coerceAtLeast(1.0)
            val rateToday = cyclesToday / secondsToday
            rateToday >= 100.0 / 86_400.0
        }

    // ── Slot utilization ─────────────────────────────────────────────────────────

    /** Active (non-terminal) Jules sessions at last update. */
    @Volatile
    var activeSlots: Int = 0
        private set

    /** Maximum concurrent slots (15). */
    const val TARGET_SLOTS = 15

    /** Call from CycleBody or JvmKanbanServer to record current slot utilization. */
    fun recordSlots(active: Int) {
        activeSlots = active.coerceIn(0, TARGET_SLOTS)
    }

    fun recordPhaseLatencies(latencies: List<PhaseLatency>, totalMs: Long, phase: String) {
        synchronizedLock(_ringLock) {
            lastPhaseLatencies = latencies
            lastPhase = phase
            lastCycleMs = totalMs
            _cyclesPerDayRing[0] = cyclesToday
        }
        // Record total cycle time in histogram for percentile queries.
        val total = totalMs.coerceAtLeast(0)
        synchronizedLock(_histogramLock) {
            _histogramSum += total
            val bucketIdx = _latencyBuckets.indexOfFirst { total <= it }
            val idx = if (bucketIdx >= 0) bucketIdx else _latencyBuckets.size
            _histogramCounters[idx]++
        }
    }

    // ── Reset (call from daemon startup) ────────────────────────────────────────

    fun reset() {
        synchronizedLock(_transitionLock) { _transitionCounts.clear() }
        synchronizedLock(_cycleLock) {
            totalCycles = 0L
            cleanCycles = 0L
            errorCycles = 0L
            cyclesToday = 0L
            _cyclesTodayResetMs = currentDayStartMs()
            _lastCycleTimestampMs = 0L
        }
        synchronizedLock(_ringLock) {
            _cyclesPerDayRing.fill(0L)
            lastPhaseLatencies = emptyList()
            lastPhase = "POLL"
            lastCycleMs = 0L
        }
        synchronizedLock(_histogramLock) {
            _histogramCounters.fill(0L)
            _histogramSum = 0L
        }
        activeSlots = 0
    }

    // ── JSON export ──────────────────────────────────────────────────────────────

    /**
     * JSON map suitable for embedding in the health socket response.
     * All values are plain primitives (no collections requiring custom serializers).
     */
    fun toJsonMap(): Map<String, Any> = buildMap {
        // Transition counts
        val tc: Map<String, Long> = synchronizedLock(_transitionLock) { _transitionCounts.toMap() }
        val cpd: LongArray = synchronizedLock(_ringLock) { _cyclesPerDayRing.copyOf() }
        val lat: List<PhaseLatency> = lastPhaseLatencies

        put("totalCycles", totalCycles)
        put("cleanCycles", cleanCycles)
        put("errorCycles", errorCycles)
        put("cyclesToday", cyclesToday)
        put("cyclesPerSecond", cyclesPerSecond)
        put("cyclesAt100PerDay", cyclesAt100PerDay)
        put("activeSlots", activeSlots)
        put("targetSlots", TARGET_SLOTS)
        put("lastPhase", lastPhase)
        put("lastCycleMs", lastCycleMs)
        put("lastUpdated", nowMs())

        // Per-transition counts as nested map
        put("transitions", tc)

        // Phase latencies as list of {phase,ms} maps
        put("phaseLatencies", lat.map { mapOf("phase" to it.phase, "ms" to it.ms) })

        // Histogram bucket counts and sum for percentiles
        val (hc, hSum) = histogramSnapshot()
        put("histogramBuckets", _latencyBuckets.toList())
        put("histogramCounts", hc.toList())
        put("histogramSumMs", hSum)

        // Last 7 days cycles-per-day
        val ringList = cpd.toList()
        put("cyclesPerDay7", ringList)
    }

    // ── Prometheus text-format export ────────────────────────────────────────────

    /**
     * Prometheus text-format scrape output.
     * First help line documents each metric; subsequent lines are TYPE + VALUE.
     */
    fun toPrometheusFormat(): String = buildString {
        val tc: Map<String, Long> = synchronizedLock(_transitionLock) { _transitionCounts.toMap() }
        val cpd: LongArray = synchronizedLock(_ringLock) { _cyclesPerDayRing.copyOf() }
        val lat: List<PhaseLatency> = lastPhaseLatencies

        // flywheel_total_cycles_total
        appendLine("# HELP flywheel_total_cycles_total Total cycles completed since daemon start")
        appendLine("# TYPE flywheel_total_cycles_total counter")
        appendLine("flywheel_total_cycles_total $totalCycles")

        // flywheel_clean_cycles_total
        appendLine("# HELP flywheel_clean_cycles_total Cycles completed with zero errors")
        appendLine("# TYPE flywheel_clean_cycles_total counter")
        appendLine("flywheel_clean_cycles_total $cleanCycles")

        // flywheel_error_cycles_total
        appendLine("# HELP flywheel_error_cycles_total Cycles that had at least one error")
        appendLine("# TYPE flywheel_error_cycles_total counter")
        appendLine("flywheel_error_cycles_total $errorCycles")

        // flywheel_cycles_today
        appendLine("# HELP flywheel_cycles_today Cycles completed today (midnight-UTC reset)")
        appendLine("# TYPE flywheel_cycles_today gauge")
        appendLine("flywheel_cycles_today $cyclesToday")

        // flywheel_cycles_per_second (instantaneous rate)
        appendLine("# HELP flywheel_cycles_per_second Instantaneous cycles per second")
        appendLine("# TYPE flywheel_cycles_per_second gauge")
        appendLine("flywheel_cycles_per_second ${fixed6(cyclesPerSecond)}")

        // flywheel_cycles_at_100_per_day (1.0 or 0.0)
        appendLine("# HELP flywheel_cycles_at_100_per_day 1 if current rate >= 100/day, 0 otherwise")
        appendLine("# TYPE flywheel_cycles_at_100_per_day gauge")
        appendLine("flywheel_cycles_at_100_per_day ${if (cyclesAt100PerDay) "1" else "0"}")

        // flywheel_slots_active
        appendLine("# HELP flywheel_slots_active Number of active (non-terminal) Jules sessions")
        appendLine("# TYPE flywheel_slots_active gauge")
        appendLine("flywheel_slots_active $activeSlots")

        // flywheel_last_cycle_ms
        appendLine("# HELP flywheel_last_cycle_ms Wall-clock milliseconds of the last completed cycle")
        appendLine("# TYPE flywheel_last_cycle_ms gauge")
        appendLine("flywheel_last_cycle_ms $lastCycleMs")

        // flywheel_phase_current
        appendLine("# HELP flywheel_phase_current FlywheelPhase name of the last completed cycle")
        appendLine("# TYPE flywheel_phase_current gauge")
        appendLine("flywheel_phase_current{phase=\"$lastPhase\"} 1")

        // flywheel_phase_latency_ms
        appendLine("# HELP flywheel_phase_latency_ms Per-phase milliseconds of the last cycle")
        appendLine("# TYPE flywheel_phase_latency_ms gauge")
        for (l in lat) {
            appendLine("flywheel_phase_latency_ms{phase=\"${l.phase}\"} ${l.ms}")
        }

        // flywheel_cycle_latency_seconds (histogram for P50/P95/P99)
        appendLine("# HELP flywheel_cycle_latency_seconds_cycle Cycle wall-clock latency histogram")
        appendLine("# TYPE flywheel_cycle_latency_seconds_cycle histogram")
        val (hc, hSum) = histogramSnapshot()
        val hTotal = hc.sum()
        // Prometheus histogram buckets are cumulative: le="X" counts every observation <= X.
        var cumulative = 0L
        for ((i, bound) in _latencyBuckets.withIndex()) {
            cumulative += hc.getOrElse(i) { 0L }
            appendLine("flywheel_cycle_latency_seconds_cycle_bucket{le=\"$bound\"} $cumulative")
        }
        appendLine("flywheel_cycle_latency_seconds_cycle_bucket{le=\"+Inf\"} $hTotal")
        appendLine("flywheel_cycle_latency_seconds_cycle_sum $hSum")
        appendLine("flywheel_cycle_latency_seconds_cycle_count $hTotal")

        // flywheel_transition_total
        appendLine("# HELP flywheel_transition_total State machine transitions")
        appendLine("# TYPE flywheel_transition_total counter")
        for ((key, count) in tc) {
            appendLine("flywheel_transition_total{transition=\"$key\"} $count")
        }

        // flywheel_cycles_per_day (last 7 days, index = days ago)
        appendLine("# HELP flywheel_cycles_per_day Cycles per calendar day, last 7 days")
        appendLine("# TYPE flywheel_cycles_per_day gauge")
        for ((i, v) in cpd.withIndex()) {
            appendLine("flywheel_cycles_per_day{daysAgo=\"$i\"} $v")
        }
    }

    /**
     * Multiplatform stand-in for `"%.6f".format(v)` (JVM-only): six fraction digits,
     * always all six, never scientific notation — Prometheus accepts nothing else.
     *
     * Rounding is half-up on the scaled binary value (`floor(x * 1e6 + 0.5)`), which can
     * differ from `%.6f` by one unit in the last digit for values that scale to an exact
     * `.5`; irrelevant for the sole caller ([cyclesPerSecond]), a gauge. Magnitudes at or
     * above ~9.2e12 overflow `Long` once scaled and are out of range here — again far
     * above anything [cyclesPerSecond] can produce.
     */
    internal fun fixed6(v: Double): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "+Inf" else "-Inf"
        val negative = v < 0.0
        val scaled = floor(abs(v) * 1_000_000.0 + 0.5).toLong()
        val whole = scaled / 1_000_000L
        val frac = (scaled % 1_000_000L).toString().padStart(6, '0')
        return buildString {
            if (negative && scaled != 0L) append('-')
            append(whole)
            append('.')
            append(frac)
        }
    }
}
