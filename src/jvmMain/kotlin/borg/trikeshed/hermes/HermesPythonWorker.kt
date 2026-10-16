package borg.trikeshed.hermes

import borg.trikeshed.graal.subvm.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import java.io.OutputStream

/** Stock Hermes and its terminal in one native-capable GraalPy worker process. */
class HermesPythonWorker(
    val configuration: PythonWorker,
    private val pen: HermesPen,
    private val output: OutputStream,
    private val error: OutputStream,
    private val stopped: (String?, String?) -> Unit = { _, _ -> },
    private val inputStarted: (String) -> String? = { null },
    private val inputFinished: (String?, Map<String, Any?>) -> Unit = { _, _ -> },
    private val progress: (String) -> Unit = {},
) : AutoCloseable {
    private var guest: ProcessIsolate? = null
    val isAlive: Boolean get() = guest?.isAlive == true
    val isClosed: Boolean get() = guest == null
    val processId: Long? get() = guest?.processId

    fun open(rows: Int, columns: Int, resume: String? = null): Map<String, Any?> {
        check(guest == null) { "Hermes worker already exists" }
        val worker = ProcessIsolate("hermes-cli", VmFacet.GRAAL_PYTHON,
            budget = Budget(statements = 0, wallMillis = 0),
            terminalOutput = output, terminalError = output,
            pythonWorker = configuration)
        guest = worker
        try {
            pen.install(worker)
            worker.delegate("sleeve_cli_input") { arguments ->
                val line = (arguments.singleOrNull() as? Teleported.Str)?.v
                    ?: error("terminal input requires text")
                inputStarted(line)?.let { Teleported.Str(it) } ?: Teleported.Null
            }
            worker.delegate("sleeve_cli_result") { arguments ->
                check(arguments.size == 2) { "terminal result requires cause and state" }
                val result = (arguments[1] as? Teleported.Str)?.v ?: error("terminal result requires JSON state")
                inputFinished((arguments[0] as? Teleported.Str)?.v,
                    reifyMap(result) + ("workerPid" to worker.processId))
                Teleported.Null
            }
            worker.delegate("sleeve_cli_closed") { arguments ->
                stopped((arguments.getOrNull(0) as? Teleported.Str)?.v,
                    (arguments.getOrNull(1) as? Teleported.Str)?.v)
                Teleported.Null
            }
            worker.delegate("sleeve_progress") { arguments ->
                progress((arguments.singleOrNull() as? Teleported.Str)?.v
                    ?: error("startup progress requires text"))
                Teleported.Null
            }
            return state(eval("""
                import trikeshed_cli_worker
                trikeshed_cli_worker.start(host.call, rows=$rows, columns=$columns,
                                          resume=${resume?.let(::jsonOf) ?: "None"})
            """.trimIndent(), "hermes-cli-start.py").also {
                check((it as? Teleported.Obj)?.v?.get("open") == Teleported.Bool(true)) {
                    "HermesCLI did not reach its terminal input loop"
                }
            })
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun eval(source: String, name: String = "hermes-cli-host.py"): Teleported =
        checkNotNull(guest) { "Hermes worker is closed" }.eval(source, name)

    fun submit(line: String): Map<String, Any?> {
        val status = status()
        if (status["open"] != true) return status + ("input_processed" to false)
        return state(eval("trikeshed_cli_worker.submit(${jsonOf(line)})", "hermes-cli-input.py"))
    }

    fun status(): Map<String, Any?> = state(eval("trikeshed_cli_worker.status()", "hermes-cli-status.py"))

    fun input(text: String) {
        checkNotNull(guest) { "Hermes worker is closed" }.pushInput(text)
    }

    fun resize(rows: Int, columns: Int) {
        if (isAlive) eval("trikeshed_cli_worker.resize($rows, $columns)", "hermes-cli-resize.py")
    }

    private fun state(value: Teleported): Map<String, Any?> {
        check(value is Teleported.Obj) { "HermesCLI did not return its state: $value" }
        return reifyMap(value.canonical()) + ("workerPid" to processId)
    }

    override fun close() {
        val worker = guest ?: return
        try {
            if (worker.isAlive) worker.eval("""
                import sys
                if 'trikeshed_cli_worker' in sys.modules:
                    sys.modules['trikeshed_cli_worker'].close()
                True
            """.trimIndent(), "hermes-cli-close.py")
        } finally {
            try { worker.close() } finally { guest = null }
        }
    }
}
