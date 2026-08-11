package borg.trikeshed.daemon

import borg.trikeshed.jules.FlywheelDriver
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
 *   2. Writes the trace JSON (the operator's live signal).
 *   3. Manages backoff/error counting for the periodicity loop.
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
            println("[HOTSWAP] CycleBody.run() invoked — bytecode rev=" + System.identityHashCode(this) + " — POST-SWAP-MARKER")

            // Read the reactive tick snapshot and emit telemetry. The reactive
            // coroutines own all computation; we only observe and trace.
            val report = driver.lastReactiveReport
            if (report != null) {
                OroborosDaemon.lastCycleReport = report
                println("[FLYWHEEL] phase=" + report.phase + " cycleMs=" + report.cycleMs + " harvested=" + report.harvested + " dispatched=" + report.dispatched + " alive=" + report.alive + "/" + report.available + " inducted=" + report.inducted + " settled=" + report.settled)
                val t = System.currentTimeMillis()
                val json = "{\"t\":" + t + ",\"c\":" + report.cycleMs + ",\"d\":" + report.harvested + ",\"p\":" + report.dispatched + ",\"a\":" + report.alive + ",\"v\":" + report.available + ",\"e\":0,\"h429\":" + report.http429 + ",\"h5x\":" + report.http5xx + "}"
                try { traceWriter?.invoke(json) } catch (_: Throwable) {}
                consecutivePollErrors.set(0)
            }
        } catch (t: Throwable) {
            System.err.println("[OROBOROS] CycleBody.run() hard failure: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
            try { consecutivePollErrors.incrementAndGet() } catch (_: Throwable) {}
        }
    }
}

/** Wrapper class that holds the live [CycleBody] reference. Declared
 *  separately so the field type is stable across retransforms. */
class CycleBodyHolder(@Volatile var body: CycleBody?)
