package metrics

import fsm.FlywheelState
import kotlin.time.TimeSource

/**
 * Flywheel telemetry — in-memory metrics with JSON and Prometheus text-format export.
 *
 * Thread-safe via synchronized access on all mutating operations. The daemon writes
 * this to the UNIX health socket on every connection and exports it via /metrics.
 */
object FlywheelMetrics {

    // ── State machine transitions ────────────────────────────────────────────────

    private val _transitionCounts = mutableMapOf<String, Long>()
    private var _lastTransitionMark = TimeSource.Monotonic.markNow()
    private val _transitionLock = Any()

    fun recordTransition(from: FlywheelState, to: FlywheelState) {
        val now = TimeSource.Monotonic.markNow()
        synchronized(_transitionLock) {
            val duration = _lastTransitionMark.elapsedNow()
            _lastTransitionMark = now
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

    private fun currentDayStartMs(): Long {
        val now = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        return now
    }

    /** Call once per completed cycle from CycleBody.run() */
    fun recordCycle(hadErrors: Boolean) {
        synchronized(_cycleLock) {
            val nowMs = System.currentTimeMillis()
            if (nowMs >= _cyclesTodayResetMs + 86_400_000L) {
                // Midnight UTC passed — reset daily counter
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

    /** Rolling window: cycles-per-day for the last 7 days (index 0 = today). */
    private val _cyclesPerDayRing = LongArray(7)
    private var _ringIndex = 0
    private val _ringLock = Any()

    /** Timestamp (epoch ms) of the most recent cycle start. */
    private var _lastCycleTimestampMs: Long = 0L

    /**
     * Cycles per second — instantaneous rate based on interval between the
     * last two completed cycles. Zero until at least two cycles have run.
     */
    val cyclesPerSecond: Double
        get() {
            synchronized(_cycleLock) {
                if (_lastCycleTimestampMs == 0L || totalCycles < 2L) return 0.0
                val elapsed = System.currentTimeMillis() - _lastCycleTimestampMs
                return if (elapsed > 0) 1_000.0 / elapsed else 0.0
            }
        }

    /**
     * True when cycles-per-day rate is at or above 100/day.
     * Compared at midnight-UTC boundary — cyclesToday / seconds-today.
     */
    val cyclesAt100PerDay: Boolean
        get() {
            synchronized(_cycleLock) {
                val msToday = System.currentTimeMillis() - _cyclesTodayResetMs
                val secondsToday = (msToday / 1_000.0).coerceAtLeast(1.0)
                val rateToday = cyclesToday / secondsToday
                return rateToday >= 100.0 / 86_400.0
            }
        }

    fun recordPhaseLatencies(latencies: List<PhaseLatency>, totalMs: Long, phase: String) {
        synchronized(_ringLock) {
            lastPhaseLatencies = latencies
            lastPhase = phase
            lastCycleMs = totalMs
            _cyclesPerDayRing[_ringIndex] = cyclesToday
        }
    }

    // ── Reset (call from daemon startup) ────────────────────────────────────────

    fun reset() {
        synchronized(_transitionLock) { _transitionCounts.clear() }
        synchronized(_cycleLock) {
            totalCycles = 0L
            cleanCycles = 0L
            errorCycles = 0L
            cyclesToday = 0L
            _cyclesTodayResetMs = currentDayStartMs()
        }
        synchronized(_ringLock) {
            _cyclesPerDayRing.fill(0L)
            _ringIndex = 0
        }
        _lastCycleTimestampMs = 0L
        lastPhaseLatencies = emptyList()
        lastPhase = "POLL"
        lastCycleMs = 0L
    }

    // ── JSON export ──────────────────────────────────────────────────────────────

    /**
     * JSON map suitable for embedding in the health socket response.
     * All values are plain primitives (no collections requiring custom serializers).
     */
    fun toJsonMap(): Map<String, Any> = buildMap {
        // Transition counts
        val tc: Map<String, Long>
        val cpd: LongArray
        val lat: List<PhaseLatency>
        synchronized(_transitionLock) { tc = _transitionCounts.toMap() }
        synchronized(_ringLock) { cpd = _cyclesPerDayRing.copyOf() }
        lat = lastPhaseLatencies

        put("totalCycles", totalCycles)
        put("cleanCycles", cleanCycles)
        put("errorCycles", errorCycles)
        put("cyclesToday", cyclesToday)
        put("cyclesPerSecond", cyclesPerSecond)
        put("cyclesAt100PerDay", cyclesAt100PerDay)
        put("lastPhase", lastPhase)
        put("lastCycleMs", lastCycleMs)
        put("lastUpdated", System.currentTimeMillis())

        // Per-transition counts as nested map
        put("transitions", tc)

        // Phase latencies as list of {phase,ms} maps
        put("phaseLatencies", lat.map { mapOf("phase" to it.phase, "ms" to it.ms) })

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
        val tc: Map<String, Long>
        val cpd: LongArray
        val lat: List<PhaseLatency>
        synchronized(_transitionLock) { tc = _transitionCounts.toMap() }
        synchronized(_ringLock) { cpd = _cyclesPerDayRing.copyOf() }
        lat = lastPhaseLatencies

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
        appendLine("flywheel_cycles_per_second ${"%.6f".format(cyclesPerSecond)}")

        // flywheel_cycles_at_100_per_day (1.0 or 0.0)
        appendLine("# HELP flywheel_cycles_at_100_per_day 1 if current rate >= 100/day, 0 otherwise")
        appendLine("# TYPE flywheel_cycles_at_100_per_day gauge")
        appendLine("flywheel_cycles_at_100_per_day ${if (cyclesAt100PerDay) "1" else "0"}")

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
}
