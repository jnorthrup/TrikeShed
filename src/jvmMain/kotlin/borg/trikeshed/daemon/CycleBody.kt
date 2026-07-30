package borg.trikeshed.daemon

import borg.trikeshed.jules.FlywheelDriver
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hot-swappable cycle body. The daemon's periodicity loop calls [run] on a
 * single instance; while that loop is alive, JVMTI can retransform this
 * class — the next call sees the new bytecode, no daemon restart.
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
    private val pollErrRef: () -> Boolean,
    private val setPollErr: (Boolean) -> Unit,
    private val runCycle: suspend () -> Unit,
    private val preflight: () -> Boolean,
) : Runnable {

    override fun run() {
        // The whole body is guarded: a failure here would otherwise escape
        // the Runnable and tear down the daemon's periodicity thread. The
        // postmortem action is always the same — log, increment the backoff
        // counter, return — so the loop continues with a delay+retry next tick.
        try {
            println("[HOTSWAP] CycleBody.run() invoked — bytecode rev=" + System.identityHashCode(this) + " — POST-SWAP-MARKER")
            setPollErr(false)
            if (!preflight()) {
                System.err.println("[OROBOROS] preflight failed; skipping cycle")
                consecutivePollErrors.incrementAndGet()
                return
            }
            try {
                // A committed conflict is a quarantine boundary. Re-entering
                // drain while markers remain reapplies every COMPLETED delta and
                // nests the same conflict again. QA or an in-flight locality
                // session must resolve the marker before the next cycle mutates
                // the tree.
                val markerProbe = ProcessBuilder("git", "grep", "-l", "^<<<<<<< ", "--")
                    .directory(repoDir)
                    .redirectErrorStream(true)
                    .start()
                val markerProbeFinished = markerProbe.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                val markerFiles = if (markerProbeFinished && markerProbe.exitValue() == 0) {
                    markerProbe.inputStream.bufferedReader().readText().trim()
                } else {
                    if (!markerProbeFinished) markerProbe.destroyForcibly()
                    ""
                }
                if (markerFiles.isNotEmpty()) {
                    System.err.println("[OROBOROS] conflict quarantine; cycle paused for: ${markerFiles.replace('\n', ',')}")
                    consecutivePollErrors.incrementAndGet()
                    return
                }

                kotlinx.coroutines.runBlocking { runCycle() }
                consecutivePollErrors.set(0)
            } catch (t: Throwable) {
                System.err.println("[OROBOROS] cycle failed: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
            }
            if (pollErrRef()) consecutivePollErrors.incrementAndGet()
            else consecutivePollErrors.set(0)
        } catch (t: Throwable) {
            // Hard failure: preflight threw (stale git lock, ENOENT on
            // repoDir), setPollErr closure blew up, or anything else escaped.
            // The loop MUST NOT die — we are the only thing keeping the wheel
            // turning. Log and let the next tick try again.
            System.err.println("[OROBOROS] CycleBody.run() hard failure: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
            try { consecutivePollErrors.incrementAndGet() } catch (_: Throwable) {}
        }
    }
}

/** Wrapper class that holds the live [CycleBody] reference. Declared
 *  separately so the field type is stable across retransforms. */
class CycleBodyHolder(@Volatile var body: CycleBody?)
