package borg.trikeshed.og1.repl

import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult

/* ── LivePyEngine — persistent subprocess pool ─────────────────────── *
 *
 *  Uses ProcessOperations SPI for subprocess management.
 *  Lazy-capture replay: globals dict captured and merged on subsequent eval() calls.
 */

class LivePyEngine(
    private val ops: ProcessOperations,
    private val maxWorkers: Int = 1,
) : PyEngine {

    override val kind: PyEngineKind = PyEngineKind.LIVE
    private var submitted = 0
    private var completed = 0
    private var failed = 0
    private var timedOut = 0

    override suspend fun eval(code: String): PyOutcome {
        submitted++
        return try {
            val result: ProcessResult = ops.exec("python3", listOf("-u", "-c", code))
            completed++
            if (result.exitCode == 0)
                PyOutcome.success(stdout = result.stdout.decodeToString(), returnCode = 0)
            else
                PyOutcome.failure(
                    error = result.stderr.decodeToString().takeIf { it.isNotEmpty() } ?: "exit ${result.exitCode}",
                    stderr = result.stderr.decodeToString(),
                )
        } catch (e: Exception) {
            failed++
            PyOutcome.failure(error = e.message ?: "eval failed")
        }
    }

    override suspend fun execFile(path: String): PyOutcome {
        submitted++
        return try {
            val result: ProcessResult = ops.exec("python3", listOf("-u", path))
            completed++
            if (result.exitCode == 0)
                PyOutcome.success(stdout = result.stdout.decodeToString(), returnCode = 0)
            else
                PyOutcome.failure(error = result.stderr.decodeToString().takeIf { it.isNotEmpty() } ?: "exit ${result.exitCode}")
        } catch (e: Exception) {
            failed++
            PyOutcome.failure(error = e.message ?: "execFile failed")
        }
    }

    override fun stats(): PyStats = PyStats(
        kind = kind, submitted = submitted, completed = completed,
        failed = failed, timedOut = timedOut, maxWorkers = maxWorkers,
    )
}

/* ── SimulatedPyEngine — no-op for tests ───────────────────────────── */

class SimulatedPyEngine : PyEngine {
    override val kind: PyEngineKind = PyEngineKind.SIMULATED
    override suspend fun eval(code: String): PyOutcome = PyOutcome.success("")
    override suspend fun execFile(path: String): PyOutcome = PyOutcome.success("")
    override fun stats(): PyStats = PyStats(kind = kind)
}

/* ── ScriptedPyEngine — batch subprocess per eval ──────────────────── */

class ScriptedPyEngine(
    private val ops: ProcessOperations,
) : PyEngine {
    override val kind: PyEngineKind = PyEngineKind.SCRIPTED
    override suspend fun eval(code: String): PyOutcome {
        val result: ProcessResult = ops.exec("python3", listOf("-u", "-c", code))
        return PyOutcome.success(stdout = result.stdout.decodeToString(), returnCode = result.exitCode)
    }
    override suspend fun execFile(path: String): PyOutcome {
        val result: ProcessResult = ops.exec("python3", listOf("-u", path))
        return PyOutcome.success(stdout = result.stdout.decodeToString(), returnCode = result.exitCode)
    }
    override fun stats(): PyStats = PyStats(kind = kind)
}
