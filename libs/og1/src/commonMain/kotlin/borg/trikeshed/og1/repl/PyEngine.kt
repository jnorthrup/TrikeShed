package borg.trikeshed.og1.repl

/* ── PyEngine — Python execution engine interface ──────────────────── *
 *
 *  Three implementations:
 *    - LivePyEngine      — persistent subprocess pool (jvmMain)
 *    - ScriptedPyEngine  — batch subprocess per eval (jvmMain)
 *    - SimulatedPyEngine — no-op for tests (jvmMain)
 *
 *  NOT sealed — implementations live in jvmMain.
 */

interface PyEngine {
    suspend fun eval(code: String): PyOutcome
    suspend fun execFile(path: String): PyOutcome
    fun stats(): PyStats
    val kind: PyEngineKind
}

enum class PyEngineKind { LIVE, SCRIPTED, SIMULATED }

/* ── PyOutcome — inline wireproto result ───────────────────────────── *
 *
 *  Fixed-size fields. No kotlin.Result clash.
 *  stdout/stderr are string-pool indices resolved on access.
 */

class PyOutcome private constructor(
    private val _stdout: String,
    private val _stderr: String,
    private val _error: String?,
    private val _returnCode: Int,
    private val _elapsedMs: Long,
) {
    val ok: Boolean get() = _error == null && _returnCode == 0
    val stdout: String get() = _stdout
    val stderr: String get() = _stderr
    val error: String? get() = _error
    val returnCode: Int get() = _returnCode
    val elapsedMs: Long get() = _elapsedMs

    companion object {
        fun success(stdout: String, stderr: String = "", returnCode: Int = 0, elapsedMs: Long = 0): PyOutcome =
            PyOutcome(stdout, stderr, null, returnCode, elapsedMs)
        fun failure(error: String, stderr: String = "", elapsedMs: Long = 0): PyOutcome =
            PyOutcome("", stderr, error, -1, elapsedMs)
    }
}

data class PyStats(
    val kind: PyEngineKind,
    val submitted: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val timedOut: Int = 0,
    val maxWorkers: Int = 1,
)

interface PyEngineProvider {
    fun engine(kind: PyEngineKind = PyEngineKind.LIVE): PyEngine
}
