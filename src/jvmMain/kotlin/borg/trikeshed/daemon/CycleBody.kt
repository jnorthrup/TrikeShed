package borg.trikeshed.daemon

import borg.trikeshed.jules.FlywheelDriver
import borg.trikeshed.jules.FlywheelPhase
import metrics.FlywheelMetrics
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hot-swappable telemetry probe. The daemon's periodicity loop calls [run]
 * on a single instance; while that loop is alive, JVMTI can retransform this
 * class — the next call sees the new bytecode, no daemon restart.
 *
 * The reactive choreography (FlywheelDriver.startReactiveCycle) is the SOLE
 * driver of poll/drain/dispatch. This body does NOT call driver.cycle() —
 * that would double-poll, double-gate, and race the reactive coroutines.
 * It is a pure observer:
 *   1. Reads [FlywheelDriver.lastReactiveReport] — the reactive tick snapshot.
 *   2. Records per-phase latency into [FlywheelMetrics].
 *   3. Writes the trace JSON (the operator's live signal).
 *   4. Manages backoff/error counting for the periodicity loop.
 *
 * Conflict markers are NOT quarantined. A conflict is pure honesty of intent —
 * two arms of a merge that vary in their approach. The wheel keeps draining
 * and dispatching right through them; the markers are evidence of progress,
 * not a stop condition.
 *
 * Edit rules (kept narrow on purpose so retransform doesn't break):
 *   - This file's class shape (fields, method signatures) is stable.
 *   - Body changes only — edit the [run] implementation.
 *   - Don't add new fields; redefine breaks if the constant pool layout
 *     changes.
 */
class CycleBody(
    private val driver: FlywheelDriver,
    private val repoDir: java.io.File,
    private val consecutivePollErrors: AtomicInteger,
    private val traceWriter: ((String) -> Unit)?,
) : Runnable {

    override fun run() {
        try {
            // Read the reactive tick snapshot and emit telemetry. The reactive
            // coroutines own all computation; we only observe and trace.
            val report = driver.lastReactiveReport
            if (report != null) {
                OroborosDaemon.lastCycleReport = report

                // Record metrics — must happen before trace write so metrics
                // reflect the current cycle even on write failure.
                val hadErrors = report.http429 > 0 || report.http5xx > 0
                FlywheelMetrics.recordCycle(hadErrors)

                // Phase latencies from FlywheelDriver's per-phase timing.
                val phaseLatencies = report.phaseLatencies.map { (phase, ms) ->
                    FlywheelMetrics.PhaseLatency(phase, ms)
                }
                FlywheelMetrics.recordPhaseLatencies(phaseLatencies, report.cycleMs, report.phase.name)
                FlywheelMetrics.recordSlots(report.alive)

                println(
                    "[FLYWHEEL] phase=" + report.phase + " cycleMs=" + report.cycleMs +
                        " harvested=" + report.harvested + " dispatched=" + report.dispatched +
                        " alive=" + report.alive + "/" + report.available +
                        " inducted=" + report.inducted + " settled=" + report.settled +
                        " archived=" + report.archived
                )

                val t = System.currentTimeMillis()
                val json = ("{" +
                    "\"t\":" + t + "," +
                    "\"c\":" + report.cycleMs + "," +
                    "\"d\":" + report.harvested + "," +
                    "\"p\":" + report.dispatched + "," +
                    "\"a\":" + report.alive + "," +
                    "\"v\":" + report.available + "," +
                    "\"e\":0," +
                    "\"h429\":" + report.http429 + "," +
                    "\"h5x\":" + report.http5xx + "," +
                    "\"phase\":\"" + report.phase.name + "\"" +
                    "}")
                try { traceWriter?.invoke(json) } catch (_: Throwable) {}
                consecutivePollErrors.set(0)
            }
        } catch (t: Throwable) {
            System.err.println(
                "[OROBOS] CycleBody.run() hard failure: " + t.javaClass.simpleName +
                    ": " + (t.message?.take(200) ?: "null")
            )
            try { consecutivePollErrors.incrementAndGet() } catch (_: Throwable) {}
        }
    }
}

/** Wrapper class that holds the live [CycleBody] reference. Declared
 *  separately so the field type is stable across retransforms. */
class CycleBodyHolder(@Volatile var body: CycleBody?)
