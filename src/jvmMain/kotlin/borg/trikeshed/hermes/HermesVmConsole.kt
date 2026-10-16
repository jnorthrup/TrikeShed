package borg.trikeshed.hermes

import borg.trikeshed.lcnc.media.CausalMediaEmission
import borg.trikeshed.lcnc.media.LcncUserSignal
import borg.trikeshed.lcnc.media.ManualMediaInput
import borg.trikeshed.lcnc.media.MediaPatchPanelDescriptor
import borg.trikeshed.lcnc.media.MediaPatchPanelId
import borg.trikeshed.lcnc.media.XtermMediaPatchPanel
import borg.trikeshed.lcnc.media.toKanbanEvent
import borg.trikeshed.lcnc.media.toMap
import borg.trikeshed.lib.*
import borg.trikeshed.terminal.VtCell
import borg.trikeshed.terminal.VtKey
import borg.trikeshed.terminal.TerminalOutputStream
import borg.trikeshed.userspace.reactor.KanbanFSM
import borg.trikeshed.vm.Teleported
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.graal.subvm.GuestException
import borg.trikeshed.graal.subvm.GuestFailure
import borg.trikeshed.graal.subvm.PythonWorker
import org.graalvm.polyglot.PolyglotException

import java.nio.file.Files
import java.nio.file.Path
import java.lang.System
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import modelmux.ModelMux
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Host terminal lane for the real Hermes CLI in a supervised GraalPy runtime. */
class HermesVmConsole(
    private val root: Path,
    private val sleeve: Path,
    columns: Int = 100,
    rows: Int = 32,
    blackboard: ConfixBlackboard = ConfixBlackboard.empty(),
    modelMuxProvider: suspend () -> ModelMux? = { null },
    muxContext: CoroutineContext = EmptyCoroutineContext,
    socketScope: CoroutineScope? = null,
    world: BtrfsWorldStore = BtrfsWorldStore.ofMemory(),
    install: (borg.trikeshed.graal.subvm.GraalBtrfsSupervisor) -> Unit = {},
    workerConfiguration: PythonWorker? = null,
    panelId: String = "default",
) : AutoCloseable {
    /** READY admits diagnostic commands after imports, provider registration and bootstrap complete. */
    enum class State { CLOSED, BOOTING, READY, FAILED }
    enum class BootPhase(val module: String?) {
        INVENTORY(null), IMPORT("hermes_cli.main"), PROVIDER("trikeshed_modelmux"), BOOTSTRAP("hermes-xterm-bootstrap.py")
    }

    data class Boot(
        val phase: BootPhase,
        val status: State,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val detail: String,
        val module: String? = null,
        val failure: String? = null,
        val traceback: String? = null,
        val revision: Long = 0,
        val wallMillis: Long = HermesPythonPort.IMPORT_WALL_MILLIS,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "phase" to phase.name.lowercase(),
            "status" to if (status == State.BOOTING) "running" else status.name.lowercase(),
            "startedAtMs" to startedAtMs,
            "updatedAtMs" to updatedAtMs,
            "detail" to detail,
            "module" to module,
            "failure" to failure,
            "traceback" to traceback,
            // Compatibility key for diagnostic-console readiness; it does not measure HermesCLI lifecycle.
            "steadyState" to (status == State.READY),
            "wallMillis" to if (phase == BootPhase.INVENTORY) null else wallMillis,
            "revision" to revision,
        )
    }

    sealed class Event {
        data class Manual(val signal: LcncUserSignal) : Event()
        data class Causal(val emission: CausalMediaEmission) : Event()
        data class StateChanged(val state: State, val detail: String, val boot: Boot? = null) : Event()
    }

    val panel = XtermMediaPatchPanel(
        MediaPatchPanelDescriptor(MediaPatchPanelId("hermes/$panelId"), "xterm-256color", "Hermes", columns, rows),
    )
    private val lock = Any()
    private val currentCause = AtomicReference<String?>(null)
    private val terminalCause = AtomicReference<String?>(null)
    private fun activeCause(): String? = terminalCause.get() ?: currentCause.get()

    /**
     * Server-side line discipline. The browser is a dumb VT client: it ships keystrokes and draws the
     * screen the panel hands back, so the cursor an operator sees *is* `terminal.cursor` rather than a
     * DOM caret in a separate input box. Editing is windowed to one row (readline's horizontal-scroll
     * discipline), which makes a redraw `CR · prompt · slice · EL · CHA` with no wrap arithmetic to get
     * wrong when the line outgrows the viewport.
     */
    private val line = StringBuilder()
    private var lineCursor = 0
    private var windowStart = 0
    private val lineHistory = mutableListOf<String>()
    private var historyIndex = 0
    private var draft: String? = null

    /**
     * Where a completed line goes. The HTTP wire sets this so a keystroke POST returns immediately and
     * the turn runs on its own dispatcher; left null (TUI, tests) the line executes inline.
     */
    @Volatile var commandSink: ((ManualMediaInput) -> Unit)? = null

    /**
     * True while a turn is running. `open()` can be reached from inside `execute()` (a first command on a
     * closed console boots the VM), and a prompt drawn there would land *before* the turn's own output,
     * so the output appends to the prompt row instead of starting a line of its own. The prompt is drawn
     * once, by whoever finishes.
     */
    @Volatile private var turnInFlight = false
    @Volatile private var modelId: String? = null
    @Volatile private var cliStarted = false
    @Volatile private var cliRequested = false
    @Volatile private var cliStatus: Map<String, Any?>? = null
    private var resumeSession: String? = null
    private val _events = MutableSharedFlow<Event>(replay = 128, extraBufferCapacity = 1024)
    val events: SharedFlow<Event> = _events.asSharedFlow()
    @Volatile var state: State = State.CLOSED; private set
    @Volatile var detail: String = "not started"; private set
    @Volatile var boot: Boot? = null; private set
    private var phaseModule: String? = null
    private var importFailure: Map<String, Any?>? = null
    private val loading = mutableListOf<String>()
    @Volatile private var inventory: HermesPortInventory? = null
    private val stdout = TerminalOutputStream { text -> causalOutput(text, activeCause()) }
    private val stderr = TerminalOutputStream { text -> causalOutput("\u001b[31m$text\u001b[0m", activeCause()) }
    private val pen = HermesPen(blackboard, modelMuxProvider = modelMuxProvider,
        muxContext = muxContext, contextId = ::activeCause)
    private val worker = workerConfiguration?.let { configuration ->
        HermesPythonWorker(configuration, pen, stdout, stderr, stopped = { session, failure ->
            synchronized(lock) {
                cliStarted = false
                cliStatus = (cliStatus ?: emptyMap()) + mapOf("open" to false, "session_id" to session, "failure" to failure)
                val next = if (failure == null) State.CLOSED else State.FAILED
                val message = failure ?: "HermesCLI closed · Start or Reset to resume"
                failTerminalInput(failure ?: "HermesCLI closed before terminal input completed")
                boot = boot?.copy(status = next, updatedAtMs = System.currentTimeMillis(),
                    detail = message, failure = failure, revision = boot!!.revision + 1)
                transition(next, message)
            }
        }, inputStarted = ::workerInputStarted, inputFinished = ::workerInputFinished) { progress ->
            bootPhase(BootPhase.BOOTSTRAP, progress, "cli.HermesCLI")
        }
    }
    private val port = HermesPythonPort(
        blackboard = blackboard, output = stdout, error = stderr,
        pen = pen,
        socketScope = socketScope,
        importPointcut = ::importPointcut,
        world = world,
        install = install,
    )
    internal var inventoryLoader: (HermesPythonPort) -> HermesPortInventory = { p ->
        require(Files.isDirectory(root)) { "Hermes source root missing: $root" }
        p.inventory(root, sleeve.takeIf(Files::isDirectory))
    }

    fun open(timestampMs: Long = System.currentTimeMillis()): State {
        synchronized(lock) {
            if (state == State.READY || state == State.BOOTING) return state
            inventory = null
            phaseModule = null
            importFailure = null
            loading.clear()
            val now = maxOf(System.currentTimeMillis(), (boot?.startedAtMs ?: 0) + 1)
            boot = if (worker != null) {
                Boot(BootPhase.IMPORT, State.BOOTING, now, now, "starting stock GraalPy worker")
            } else {
                Boot(BootPhase.INVENTORY, State.BOOTING, now, now, "inventorying ${root.toAbsolutePath()}")
            }
            transition(State.BOOTING, boot!!.detail)
            causalOutput("\u001b[2J\u001b[H\u001b[1;38;5;208mTrikeShed Hermes VM · xterm-256color\u001b[0m\r\n", null, timestampMs)
            causalOutput("sleeve · ${boot!!.detail}\r\n", null, timestampMs)
        }
        return try {
            if (worker != null) return openWorker(timestampMs)
            val scanned = inventoryLoader(port)
            inventory = scanned
            cliRequested = "cli" in scanned.modules && "trikeshed_cli" in scanned.modules
            causalOutput(
                "inventory ${scanned.modules.size} modules · ${scanned.ready} ready · " +
                    "${scanned.blockedNative} native-blocked · ${scanned.blockedTransitive} transitive\r\n",
                null,
                timestampMs,
            )
            bootPhase(BootPhase.IMPORT, "importing hermes_cli.main in no-native GraalPy (${HermesPythonPort.IMPORT_WALL_MILLIS / 1000}s execution limit)", "hermes_cli.main")
            port.importInVm(scanned, "hermes_cli.main")
            if ("providers.base" in scanned.modules) {
                bootPhase(BootPhase.PROVIDER, "registering the Hermes model provider", "trikeshed_modelmux")
                port.evalInVm("import trikeshed_modelmux\ntrikeshed_modelmux.register()\nTrue", "hermes-model-provider.py")
            }
            if ("hermes_cli.oneshot" in scanned.modules) {
                bootPhase(BootPhase.BOOTSTRAP, "binding the Hermes runtime to ModelMux", "hermes_cli.runtime_provider")
                port.evalInVm("""
                    from hermes_cli import runtime_provider
                    from agent import auxiliary_client
                    import trikeshed_modelmux as mux
                    mux.bind_runtime(runtime_provider, auxiliary_client)
                    True
                """.trimIndent(), "hermes-console-runtime.py")
                if ("run_agent" in scanned.modules) {
                    for (module in s_["run_agent", "agent.agent_init", "agent.conversation_loop", "hermes_cli.mcp_startup"].view) {
                        bootPhase(BootPhase.BOOTSTRAP, "importing the Hermes runtime: $module", module)
                        port.evalInVm("import importlib\nimportlib.import_module(${pythonString(module)})\nTrue", "hermes-console-runtime.py")
                    }
                }
            }
            if (cliRequested) {
                bootPhase(BootPhase.BOOTSTRAP, "initializing HermesCLI and its session store", "cli.HermesCLI")
                val started = port.evalInVm("""
                    import trikeshed_cli
                    import trikeshed_files
                    from cli import HermesCLI
                    __hermes_cli = HermesCLI(model=mux.select_model(), provider=mux.PROVIDER,
                                            base_url=mux.BASE_URL)
                    if __hermes_cli._session_db_unavailable or __hermes_cli._session_db is None:
                        from hermes_state import get_last_init_error
                        raise RuntimeError('HermesCLI session store is unavailable: ' +
                                           (get_last_init_error() or 'initialization failed without a storage diagnostic'))
                    __hermes_cli.run()
                    assert trikeshed_cli.status()['open']
                    trikeshed_cli.status()
                """.trimIndent(), "hermes-cli-start.py")
                check(started is Teleported.Obj) { "HermesCLI did not return its state" }
                cliStatus = reifyMap(started.canonical())
                modelId = cliStatus?.get("model") as? String
                cliStarted = true
            } else {
                bootPhase(BootPhase.BOOTSTRAP, "initializing the Python diagnostic console", "hermes-xterm-bootstrap.py")
                port.evalInVm(CONSOLE_BOOTSTRAP, "hermes-xterm-bootstrap.py")
            }
            val ready = if (cliStarted) "HermesCLI ready" else "Python diagnostic console ready"
            synchronized(lock) {
                boot = boot!!.copy(status = State.READY, updatedAtMs = System.currentTimeMillis(), detail = ready, revision = boot!!.revision + 1)
                transition(State.READY, boot!!.detail)
            }
            causalOutput("\u001b[32m$ready\u001b[0m · " + if (cliStarted) "/help for Hermes commands\r\n" else ":help for console commands\r\n", null, timestampMs)
            if (!turnInFlight) promptAndRedraw(timestampMs)
            State.READY
        } catch (t: Throwable) {
            val interrupted = generateSequence(t) { it.cause }.filterIsInstance<GuestException>().any { it.kind == GuestFailure.INTERRUPTED }
            val evidence = importFailure?.takeIf { pointcut ->
                if (interrupted) pointcut["origin"] == "host"
                // Entry failures close the candidate after its final import callback. Later evals
                // retain their context, so compare the thrown exception rather than its message.
                else if (boot!!.phase == BootPhase.IMPORT) true
                else runCatching {
                    val guest = generateSequence(t) { it.cause }.filterIsInstance<PolyglotException>().firstOrNull()?.guestObject
                    guest != null && guest.context.eval("python", "id").execute(guest).toString() == pointcut["exceptionId"]
                }.getOrDefault(false)
            }
            val failure = if (interrupted) t.message ?: t.toString()
                else (evidence?.get("error") as? String)?.takeIf(String::isNotBlank) ?: (t.message ?: t.toString())
            val trace = (evidence?.get("traceback") as? String)?.takeIf(String::isNotBlank)
                ?: runCatching {
                    generateSequence(t) { it.cause }.filterIsInstance<PolyglotException>().firstOrNull()
                        ?.polyglotStackTrace?.filter { it.isGuestFrame }?.joinToString("\n")?.takeIf(String::isNotBlank)
                }.getOrNull()
            val failed = boot!!.copy(status = State.FAILED, updatedAtMs = System.currentTimeMillis(),
                detail = "${boot!!.phase.name.lowercase()} failed; ${if (cliRequested) "HermesCLI" else "Python diagnostic console"} unavailable" +
                    if (interrupted) "; guest interrupted (${HermesPythonPort.IMPORT_WALL_MILLIS / 1000}s execution limit)" else "",
                module = evidence?.get("module") as? String ?: loading.lastOrNull() ?: phaseModule,
                failure = failure, traceback = trace, revision = boot!!.revision + 1)
            try { port.close() } catch (closing: Throwable) { t.addSuppressed(closing) }
            synchronized(lock) {
                boot = failed
                transition(State.FAILED, "${failed.detail}: $failure")
            }
            causalOutput("\u001b[31mboot failed at ${failed.phase.name.lowercase()}${failed.module?.let { " ($it)" }.orEmpty()}: $failure\r\n${failed.detail}\u001b[0m\r\n", null, timestampMs)
            if (trace != null) causalOutput(trace.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n", null, timestampMs)
            if (!turnInFlight) promptAndRedraw(timestampMs)
            State.FAILED
        }
    }

    private fun openWorker(timestampMs: Long): State {
        cliRequested = true
        worker!!.close()
        boot = boot!!.copy(wallMillis = worker!!.configuration.timeoutMillis)
        bootPhase(BootPhase.IMPORT, "starting stock GraalPy in a dedicated worker", "cli")
        val snapshot = panel.snapshot()
        cliStatus = worker!!.open(snapshot.rows, snapshot.columns, resumeSession)
        resumeSession = null
        modelId = cliStatus?.get("model") as? String
        cliStarted = true
        synchronized(lock) {
            boot = boot!!.copy(status = State.READY, updatedAtMs = System.currentTimeMillis(),
                detail = "HermesCLI ready · stock GraalPy worker ${worker.processId}", revision = boot!!.revision + 1)
            transition(State.READY, boot!!.detail)
        }
        return state
    }

    /** Complete line submission. Web/TUI/desktop line disciplines all converge here. */
    fun submit(command: String, timestampMs: Long = System.currentTimeMillis()): LcncUserSignal =
        execute(prepareCommand(command, timestampMs), timestampMs)

    fun prepareCommand(command: String, timestampMs: Long = System.currentTimeMillis()): ManualMediaInput = synchronized(lock) {
        require(command.length <= XtermMediaPatchPanel.MAX_SIGNAL_PAYLOAD) { "command exceeds ${XtermMediaPatchPanel.MAX_SIGNAL_PAYLOAD} characters" }
        panel.manualCommand(command, timestampMs).also(::emitManual)
    }

    fun execute(manual: ManualMediaInput, timestampMs: Long = System.currentTimeMillis(), echo: Boolean = true): LcncUserSignal {
        val command = manual.signal.payload
        currentCause.set(manual.signal.id)
        turnInFlight = true
        if (echo) causalOutput(command + "\r\n", manual.signal.id, timestampMs)
        if (state == State.CLOSED) open(timestampMs)
        if (state != State.READY) {
            causalOutput("\u001b[31mconsole unavailable: $detail\u001b[0m\r\n", manual.signal.id, timestampMs)
            reject(manual, "console unavailable: $detail", timestampMs)
            currentCause.set(null)
            turnInFlight = false
            promptAndRedraw(timestampMs)
            return manual.signal
        }
        try {
            dispatch(command.trim(), manual.signal.id, timestampMs)
            val completed = panel.causalCompleted(manual.signal.id, timestampMs)
            emitCausal(completed)
        } catch (t: Throwable) {
            val message = t.message ?: t.toString()
            if (worker != null && state == State.READY && !worker.isAlive) {
                cliStarted = false
                transition(State.FAILED, "Hermes worker stopped: $message")
            }
            causalOutput("\u001b[31m$message\u001b[0m\r\n", manual.signal.id, timestampMs)
            emitCausal(panel.causalFailed(manual.signal.id, timestampMs, message))
        } finally {
            currentCause.set(null)
            turnInFlight = false
            promptAndRedraw(timestampMs)
        }
        return manual.signal
    }

    fun reject(manual: ManualMediaInput, detail: String, timestampMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        emitCausal(panel.causalFailed(manual.signal.id, timestampMs, detail))
    }

    /**
     * A run of typed or pasted characters. Printables enter the line buffer; the C0 controls a browser
     * folds into text (Ctrl-letter, CR, DEL) are the editing verbs, so the wire never has to know which
     * of the two shapes a keystroke arrived in.
     */
    fun manualText(text: String, timestampMs: Long = System.currentTimeMillis(), paste: Boolean = false): ManualMediaInput = synchronized(lock) {
        val manual = panel.manualText(text, timestampMs, paste).also(::emitManual)
        if (worker != null) {
            checkWorker()
            check(state == State.READY) { detail }
            worker.input(if (paste) "\u001b[200~${text}\u001b[201~" else text)
            return@synchronized manual
        }
        var index = 0
        while (index < text.length) {
            when (val ch = text[index]) {
                '\r', '\n' -> {
                    // CRLF is one submission, not two.
                    if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    submitLine(timestampMs)
                }
                '\u0003' -> cancelLine(timestampMs)                                   // Ctrl-C
                '\u0001' -> { lineCursor = 0; redrawLine(timestampMs) }               // Ctrl-A
                '\u0005' -> { lineCursor = line.length; redrawLine(timestampMs) }     // Ctrl-E
                '\u0002' -> moveCursor(-1, timestampMs)                               // Ctrl-B
                '\u0006' -> moveCursor(1, timestampMs)                                // Ctrl-F
                '\u000c' -> { causalOutput("\u001b[2J\u001b[H", null, timestampMs); promptAndRedraw(timestampMs) }
                '\u0015' -> { line.delete(0, lineCursor); lineCursor = 0; redrawLine(timestampMs) }  // Ctrl-U
                '\u000b' -> { line.setLength(lineCursor); redrawLine(timestampMs) }   // Ctrl-K
                '\u0017' -> killWord(timestampMs)                                     // Ctrl-W
                '\b', '\u007f' -> backspace(timestampMs)
                else -> if (ch >= ' ') insert(ch, timestampMs)                        // other C0 is inert
            }
            index++
        }
        manual
    }

    /** A named VT220 key. Editing keys act on the line; anything else is provenance only. */
    fun manualKey(
        key: VtKey,
        timestampMs: Long = System.currentTimeMillis(),
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): ManualMediaInput = synchronized(lock) {
        val manual = panel.manualKey(key, timestampMs, ctrl, alt, shift).also(::emitManual)
        if (worker != null) {
            checkWorker()
            check(state == State.READY) { detail }
            worker.input(manual.input)
            return@synchronized manual
        }
        when (key) {
            VtKey.ENTER -> submitLine(timestampMs)
            VtKey.BACKSPACE -> backspace(timestampMs)
            VtKey.DELETE -> if (lineCursor < line.length) { line.deleteCharAt(lineCursor); redrawLine(timestampMs) }
            VtKey.LEFT -> moveCursor(if (ctrl) -wordLeftSpan() else -1, timestampMs)
            VtKey.RIGHT -> moveCursor(if (ctrl) wordRightSpan() else 1, timestampMs)
            VtKey.HOME -> { lineCursor = 0; redrawLine(timestampMs) }
            VtKey.END -> { lineCursor = line.length; redrawLine(timestampMs) }
            VtKey.UP -> recallHistory(-1, timestampMs)
            VtKey.DOWN -> recallHistory(1, timestampMs)
            VtKey.ESCAPE -> cancelLine(timestampMs)
            VtKey.TAB -> insert(' ', timestampMs)
            else -> Unit
        }
        manual
    }

    /** The visible line buffer — the operator's uncommitted input. Exposed for tests and TUI parity. */
    fun lineState(): Pair<String, Int> = synchronized(lock) { line.toString() to lineCursor }

    private fun insert(ch: Char, timestampMs: Long) {
        if (line.length >= XtermMediaPatchPanel.MAX_SIGNAL_PAYLOAD) return
        line.insert(lineCursor, ch); lineCursor++
        redrawLine(timestampMs)
    }

    private fun backspace(timestampMs: Long) {
        if (lineCursor <= 0) return
        line.deleteCharAt(lineCursor - 1); lineCursor--
        redrawLine(timestampMs)
    }

    private fun moveCursor(delta: Int, timestampMs: Long) {
        val next = (lineCursor + delta).coerceIn(0, line.length)
        if (next == lineCursor) return
        lineCursor = next
        redrawLine(timestampMs)
    }

    private fun wordLeftSpan(): Int {
        var i = lineCursor
        while (i > 0 && line[i - 1] == ' ') i--
        while (i > 0 && line[i - 1] != ' ') i--
        return lineCursor - i
    }

    private fun wordRightSpan(): Int {
        var i = lineCursor
        while (i < line.length && line[i] == ' ') i++
        while (i < line.length && line[i] != ' ') i++
        return i - lineCursor
    }

    private fun killWord(timestampMs: Long) {
        val span = wordLeftSpan()
        if (span == 0) return
        line.delete(lineCursor - span, lineCursor); lineCursor -= span
        redrawLine(timestampMs)
    }

    private fun cancelLine(timestampMs: Long) {
        causalOutput("^C\r\n", null, timestampMs)
        line.setLength(0); lineCursor = 0; windowStart = 0; draft = null
        historyIndex = lineHistory.size
        promptAndRedraw(timestampMs)
    }

    private fun recallHistory(delta: Int, timestampMs: Long) {
        if (lineHistory.isEmpty()) return
        if (historyIndex == lineHistory.size && delta < 0) draft = line.toString()
        val next = (historyIndex + delta).coerceIn(0, lineHistory.size)
        if (next == historyIndex) return
        historyIndex = next
        val recalled = if (historyIndex == lineHistory.size) draft.orEmpty() else lineHistory[historyIndex]
        line.setLength(0); line.append(recalled); lineCursor = line.length
        redrawLine(timestampMs)
    }

    /**
     * Hand the completed line to the same manual→causal path a scripted command takes, so provenance is
     * identical whether a line was typed or POSTed whole.
     */
    private fun submitLine(timestampMs: Long) {
        val command = line.toString()
        line.setLength(0); lineCursor = 0; windowStart = 0; draft = null
        causalOutput("\r\n", null, timestampMs)
        if (command.isNotBlank()) {
            lineHistory.removeAll { it == command }
            lineHistory.add(command)
            while (lineHistory.size > HISTORY_LIMIT) lineHistory.removeAt(0)
        }
        historyIndex = lineHistory.size
        if (command.isBlank()) { promptAndRedraw(timestampMs); return }
        val accepted = runCatching { prepareCommand(command, timestampMs) }.getOrElse {
            causalOutput("\u001b[31m${it.message ?: it}\u001b[0m\r\n", null, timestampMs)
            promptAndRedraw(timestampMs); return
        }
        val sink = commandSink
        if (sink != null) sink(accepted) else execute(accepted, timestampMs, echo = false)
    }

    /**
     * Redraw the editable row. `windowStart` slides so the cursor always sits inside the viewport, which
     * keeps a line longer than the terminal legible without ever wrapping onto a row the console owns.
     */
    private fun redrawLine(timestampMs: Long) {
        val width = (panel.terminal.columns - PROMPT_WIDTH - 1).coerceAtLeast(8)
        if (lineCursor < windowStart) windowStart = lineCursor
        if (lineCursor - windowStart > width) windowStart = lineCursor - width
        if (windowStart > 0 && line.length - windowStart < width) windowStart = (line.length - width).coerceAtLeast(0)
        val slice = line.substring(windowStart, minOf(line.length, windowStart + width))
        causalOutput(
            "\r$PROMPT$slice\u001b[K\u001b[${PROMPT_WIDTH + (lineCursor - windowStart) + 1}G",
            null,
            timestampMs,
        )
    }

    private fun promptAndRedraw(timestampMs: Long) {
        if (worker != null) return
        windowStart = 0
        redrawLine(timestampMs)
    }

    fun resize(columns: Int, rows: Int, timestampMs: Long = System.currentTimeMillis()): CausalMediaEmission {
        if (state == State.READY && worker?.isAlive == true) worker.resize(rows, columns)
        return synchronized(lock) { panel.manualResize(columns, rows, timestampMs).also(::emitCausal) }
    }

    fun snapshotMap(): Map<String, Any?> = synchronized(lock) {
        checkWorker()
        val snapshot = panel.snapshot()
        val scanned = inventory
        linkedMapOf(
            "state" to state.name.lowercase(),
            "detail" to detail,
            "boot" to boot?.toMap(),
            "model" to modelId,
            "runtime" to if (cliRequested) "hermes-cli" else "python-diagnostic",
            "cli" to cliStatus,
            "panel" to mapOf(
                "id" to panel.descriptor.id.value,
                "kind" to panel.descriptor.kind,
                "title" to (snapshot.title.ifBlank { panel.descriptor.title }),
                "columns" to snapshot.columns,
                "rows" to snapshot.rows,
                "revision" to snapshot.revision,
                "cursor" to mapOf("row" to snapshot.cursor.row, "column" to snapshot.cursor.column, "visible" to snapshot.cursor.visible),
                "alternateScreen" to snapshot.alternateScreen,
                "applicationCursorKeys" to snapshot.applicationCursorKeys,
                "lines" to snapshot.lines.view.map { row -> row.view.map(VtCell::toMap) },
                "scrollback" to snapshot.scrollback.view.map { row -> row.view.map(VtCell::toMap) },
            ),
            "inventory" to if (scanned == null) null else mapOf(
                "modules" to scanned.modules.size,
                "ready" to scanned.ready,
                "blockedNative" to scanned.blockedNative,
                "blockedTransitive" to scanned.blockedTransitive,
                "ontologySpineCid" to scanned.ontology.cid.value,
            ),
            "signals" to panel.signals().view.toList().takeLast(128).map(LcncUserSignal::toMap),
        )
    }

    fun terminalMetaMap(): Map<String, Any?> = synchronized(lock) {
        panel.snapshot(scrollbackRows = 0).let { snapshot -> mapOf(
            "revision" to snapshot.revision,
            "columns" to snapshot.columns,
            "rows" to snapshot.rows,
            "title" to snapshot.title,
            "cursor" to mapOf("row" to snapshot.cursor.row, "column" to snapshot.cursor.column, "visible" to snapshot.cursor.visible),
        ) }
    }

    private fun dispatch(command: String, cause: String, timestampMs: Long) {
        if (worker != null) {
            runHermes(command)
            return
        }
        when {
            command.isEmpty() -> Unit
            command == ":help" -> causalOutput(HELP, cause, timestampMs)
            command == ":env" || command == "env" -> {
                val curated = borg.trikeshed.graal.subvm.GuestEnvironment.curated()
                val survey = borg.trikeshed.graal.subvm.GuestEnvironment.surveyHostEnvironment()
                causalOutput("curated guest env (${curated.size}):\r\n", cause, timestampMs)
                for ((k, v) in curated.entries.sortedBy { it.key }) causalOutput("  $k=$v\r\n", cause, timestampMs)
                val blocked = survey[borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.BLOCKED].orEmpty().sorted().take(20)
                val deferred = survey[borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.DEFERRED].orEmpty().sorted().take(20)
                causalOutput("host blocked (sample): ${blocked.joinToString()}\r\n", cause, timestampMs)
                causalOutput("host deferred (sample): ${deferred.joinToString()}\r\n", cause, timestampMs)
                // Prove the sleeve is live in the same GraalPy context
                runCatching {
                    val r = port.evalInVm("import os, json; json.dumps(dict(os.environ))", "diag-env.py")
                    causalOutput("guest os.environ: $r\r\n", cause, timestampMs)
                }.onFailure { causalOutput("guest os.environ unavailable: ${it.message}\r\n", cause, timestampMs) }
            }
            command == ":status" -> {
                val scanned = inventory
                causalOutput(
                    "state=${state.name.lowercase()} vm=${port.vmStarted} model=${modelId ?: "host-default"} modules=${scanned?.modules?.size ?: 0} " +
                        "ready=${scanned?.ready ?: 0} blocked=${(scanned?.blockedNative ?: 0) + (scanned?.blockedTransitive ?: 0)}\r\n",
                    cause,
                    timestampMs,
                )
            }
            command == ":clear" -> causalOutput("\u001b[2J\u001b[H", cause, timestampMs)
            command == ":models" -> {
                val catalog = port.evalInVm("import trikeshed_modelmux as mux\nprint(mux._call('mux_catalog'))\nTrue", "hermes-models.py")
                check(catalog == Teleported.Bool(true)) { "model catalog unavailable" }
            }
            command == ":model" -> {
                modelId = null
                causalOutput("model selection follows the host default\r\n", cause, timestampMs)
            }
            command.startsWith(":model ") -> {
                val id = command.removePrefix(":model ").trim()
                require(id.isNotEmpty()) { "model id is blank" }
                val selected = port.evalInVm("import trikeshed_modelmux as mux\nmux.select_model(${pythonString(id)})", "hermes-model.py")
                check(selected is Teleported.Str && selected.v == id) { "model selection failed: $selected" }
                modelId = id
                causalOutput("model=$id provider=trikeshed\r\n", cause, timestampMs)
            }
            command == ":modules" -> {
                val scanned = inventory ?: return
                for (module in scanned.modules.values.sortedBy { it.name }.take(200)) {
                    causalOutput("${module.status.name.padEnd(18)} ${module.name}\r\n", cause, timestampMs)
                }
            }
            command == ":gaps" -> {
                val scanned = inventory ?: return
                for (gap in scanned.significantGaps().view) causalOutput(
                    "${gap.root.padEnd(24)} impact=${gap.impacted} direct=${gap.direct} deferred=${gap.deferred}\r\n",
                    cause,
                    timestampMs,
                )
            }
            command.startsWith(":eval ") -> {
                val result = port.evalInVm(command.removePrefix(":eval "), "hermes-console-eval.py")
                causalOutput("$result\r\n", cause, timestampMs)
            }
            command.startsWith(":import ") -> {
                val module = command.removePrefix(":import ").trim()
                require(module.matches(MODULE)) { "invalid module name" }
                val result = port.evalInVm("import importlib\nimportlib.import_module(${pythonString(module)})\nTrue", "hermes-console-import.py")
                causalOutput("import $module: $result\r\n", cause, timestampMs)
            }
            command.startsWith(":hermes ") -> runHermes(command.removePrefix(":hermes "))
            else -> runHermes(command)
        }
    }

    private fun runHermes(prompt: String) {
        require(prompt.isNotBlank()) { "prompt is blank" }
        if (worker != null) {
            cliStatus = worker.submit(prompt)
            modelId = cliStatus?.get("model") as? String
            if (cliStatus?.get("open") != true) {
                cliStarted = false
                transition(State.CLOSED, "HermesCLI exited")
                worker.close()
            }
            workerInputFailure(requireNotNull(cliStatus))?.let { error(it) }
            return
        }
        if (cliStarted) {
            val result = port.evalInVm("trikeshed_cli.submit(${pythonString(prompt)})", "hermes-cli-input.py")
            val status = (result as? Teleported.Obj)?.v
            check(status != null) { "HermesCLI did not return its state" }
            cliStatus = reifyMap(result.canonical())
            modelId = cliStatus?.get("model") as? String
            if (status?.get("open") == Teleported.Bool(false)) {
                cliStarted = false
                transition(State.CLOSED, "HermesCLI exited")
            }
            val turn = (status["turn"] as? Teleported.Obj)?.v
            check(status["turn_attempted"] != Teleported.Bool(true) || turn != null) {
                "HermesCLI could not complete the turn; see the CLI output"
            }
            check(turn == null || (turn["completed"] != Teleported.Bool(false) &&
                turn["failed"] != Teleported.Bool(true) && turn["interrupted"] != Teleported.Bool(true) &&
                (turn["error"] == null || turn["error"] == Teleported.Null))) {
                (turn?.get("error") as? Teleported.Str)?.v ?: "HermesCLI turn did not complete"
            }
            return
        }
        val result = port.evalInVm("__trikeshed_hermes(${pythonString(prompt)}, ${modelId?.let(::pythonString) ?: "None"})", "hermes-console-turn.py")
        check(result is Teleported.Num && result.v == 0L) { "hermes exit $result" }
    }

    private fun causalOutput(text: String, cause: String?, timestampMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (text.isEmpty()) return@synchronized
        emitCausal(panel.causalOutput(text, cause, timestampMs))
    }

    /** The donor's input loop admits a complete terminal line, not each browser keystroke. */
    internal fun workerInputStarted(line: String): String = synchronized(lock) {
        check(terminalCause.get() == null) { "Hermes terminal input is already active" }
        prepareCommand(line).signal.id.also(terminalCause::set)
    }

    internal fun workerInputFinished(cause: String?, result: Map<String, Any?>) = synchronized(lock) {
        check(cause != null && terminalCause.get() == cause) { "Hermes terminal result has no matching input" }
        try {
            cliStatus = result
            modelId = result["model"] as? String
            val failure = workerInputFailure(result)
            val now = System.currentTimeMillis()
            emitCausal(if (failure == null) panel.causalCompleted(cause, now) else panel.causalFailed(cause, now, failure))
        } finally {
            terminalCause.set(null)
        }
    }

    /** Retire a stopped/failed terminal; normal close reaches this only after worker drain. */
    private fun failTerminalInput(failure: String) = synchronized(lock) {
        terminalCause.getAndSet(null)?.let { cause ->
            emitCausal(panel.causalFailed(cause, System.currentTimeMillis(), failure))
        }
    }

    private fun workerInputFailure(result: Map<String, Any?>): String? {
        (result["input_error"] as? String)?.let { return it }
        if (result["input_processed"] != true) return "HermesCLI did not process input"
        val turn = result["turn"] as? Map<*, *>
        if (result["turn_attempted"] == true && turn == null)
            return "HermesCLI could not complete the turn; see the CLI output"
        if (turn != null && (turn["completed"] != true || turn["failed"] == true || turn["partial"] == true ||
                turn["interrupted"] == true || turn["error"] != null))
            return turn["error"] as? String ?: "HermesCLI turn did not complete"
        return null
    }


    private fun emitManual(input: ManualMediaInput) {
        land(input.signal)
        _events.tryEmit(Event.Manual(input.signal))
    }

    private fun emitCausal(emission: CausalMediaEmission) {
        land(emission.signal)
        _events.tryEmit(Event.Causal(emission))
    }

    private fun importPointcut(pointcut: Map<String, Any?>) = synchronized(lock) {
        if (state != State.BOOTING) return@synchronized
        val module = pointcut["module"] as? String ?: return@synchronized
        when (pointcut["status"]) {
            "loading" -> {
                importFailure = null
                loading.add(module)
                boot = boot!!.copy(updatedAtMs = System.currentTimeMillis(), module = module, detail = "loading $module", revision = boot!!.revision + 1)
            }
            "loaded" -> {
                loading.remove(module)
                importFailure = null
                boot = boot!!.copy(updatedAtMs = System.currentTimeMillis(), module = loading.lastOrNull() ?: module, detail = "loaded $module", revision = boot!!.revision + 1)
            }
            "failed", "refused" -> {
                loading.remove(module)
                val prior = importFailure
                val sameException = pointcut["exceptionId"] != null && pointcut["exceptionId"] == prior?.get("exceptionId")
                val sameHostFailure = pointcut["origin"] == "host" && prior?.get("origin") == "host"
                if (!sameException && !sameHostFailure) importFailure = pointcut
                else if (prior?.get("traceback") == null && pointcut["traceback"] != null) {
                    importFailure = prior!! + ("traceback" to pointcut["traceback"])
                }
                boot = boot!!.copy(updatedAtMs = System.currentTimeMillis(), module = importFailure!!["module"] as? String, detail = "import ${pointcut["status"]}: $module", revision = boot!!.revision + 1)
            }
            else -> return@synchronized
        }
        transition(State.BOOTING, boot!!.detail)
    }

    private fun bootPhase(phase: BootPhase, value: String, module: String? = null) = synchronized(lock) {
        phaseModule = module ?: phase.module
        importFailure = null
        loading.clear()
        boot = boot!!.copy(phase = phase, updatedAtMs = System.currentTimeMillis(), detail = value, module = phaseModule, revision = boot!!.revision + 1)
        transition(State.BOOTING, value)
        causalOutput("sleeve · $value\r\n", null)
    }

    private fun transition(next: State, value: String) {
        state = next; detail = value
        _events.tryEmit(Event.StateChanged(next, value, boot))
    }

    private fun checkWorker() {
        if (worker != null && state == State.READY && !worker.isAlive) {
            val failure = "Hermes worker exited · Reset resumes this conversation"
            failTerminalInput(failure)
            cliStarted = false
            cliStatus = cliStatus?.plus("open" to false)
            boot = boot?.copy(status = State.FAILED, updatedAtMs = System.currentTimeMillis(),
                detail = failure, failure = failure, revision = boot!!.revision + 1)
            transition(State.FAILED, failure)
        }
    }

    fun fail(failure: Throwable) = synchronized(lock) {
        val message = failure.message ?: failure.toString()
        boot = boot?.copy(status = State.FAILED, updatedAtMs = System.currentTimeMillis(),
            detail = message, failure = message, revision = boot!!.revision + 1)
        transition(State.FAILED, message)
    }

    private fun land(signal: LcncUserSignal) {
        val map = signal.toMap()
        runCatching { port.blackboard().put("hermes/console/signal/${signal.id}", map, "xterm") }
        KanbanFSM.kanbanEvents.tryEmit(signal.toKanbanEvent())
    }

    /** The console lane calls this after admitted turns, retaining the native session on reset. */
    fun reset(resume: Boolean = true) {
        val session = if (resume) {
            val status = worker?.takeIf { it.isAlive }?.let { current -> runCatching { current.status() }.getOrNull() } ?: cliStatus
            status?.get("session_id") as? String
        } else null
        try { close() } catch (failure: Throwable) {
            if (worker?.isClosed != true) throw failure
            synchronized(lock) {
                emitCausal(panel.causalFailed(null, System.currentTimeMillis(),
                    "Previous Hermes worker retired with cleanup failure: ${failure.message ?: failure}"))
            }
        }
        synchronized(lock) {
            cliStatus = null
            line.clear()
            lineCursor = 0
            windowStart = 0
            resumeSession = session
        }
        causalOutput("\u001bc\u001b[3J", null)
        open()
    }

    override fun close() {
        val started = synchronized(lock) {
            val started = cliStarted
            cliStarted = false
            boot = boot?.let { it.copy(status = State.CLOSED, updatedAtMs = System.currentTimeMillis(), revision = it.revision + 1) }
            transition(State.CLOSED, "closing")
            started
        }
        try {
            if (worker != null) worker.close()
            else if (started && port.vmStarted) port.evalInVm("trikeshed_cli.close()", "hermes-cli-close.py")
        } finally {
            try {
                try { port.close() } finally { stdout.close(); stderr.close() }
            } finally {
                synchronized(lock) {
                    failTerminalInput("Hermes console closed before terminal input completed")
                    transition(State.CLOSED, "closed")
                }
            }
        }
    }

    companion object {
        private val MODULE = Regex("[A-Za-z_][A-Za-z0-9_.]*")
        private const val HISTORY_LIMIT = 500
        /** SGR 208 on a 256-colour panel; PROMPT_WIDTH is its *visible* width, which the SGR does not add to. */
        private const val PROMPT = "\u001b[38;5;208mhermes>\u001b[0m "
        private const val PROMPT_WIDTH = 8
        // Use real ESC (\u001B) via string template — triple-quoted raw strings do not interpret \u escapes,
        // which previously rendered as literal "\u001b[1m" and broke the two-column VT layout.
        private val ESC = "\u001B"
        private val HELP = "${ESC}[1m:status${ESC}[0m        VM and sleeve state\r\n" +
            "${ESC}[1m:modules${ESC}[0m       first 200 module dispositions\r\n" +
            "${ESC}[1m:gaps${ESC}[0m          ranked native dependency gaps\r\n" +
            "${ESC}[1m:eval PYTHON${ESC}[0m   evaluate inside the supervised GraalPy context\r\n" +
            "${ESC}[1m:import MODULE${ESC}[0m  import one module through the sleeve\r\n" +
            "${ESC}[1m:hermes PROMPT${ESC}[0m run a real Hermes one-shot turn\r\n" +
            "${ESC}[1m:models${ESC}[0m        current host model catalog\r\n" +
            "${ESC}[1m:model ID${ESC}[0m      select a catalog model; :model follows the host default\r\n" +
            "${ESC}[1m:clear${ESC}[0m         reset the viewport      \r\n" +
            "Any other line is a Hermes prompt.\r\n" +
            "${ESC}[1m:env${ESC}[0m            curated guest environment (diagnostic)\r\n"
        private val CONSOLE_BOOTSTRAP = """
            def __trikeshed_hermes(prompt, model=None):
                from trikeshed_modelmux import run_oneshot
                return run_oneshot(prompt, model)
            True
        """.trimIndent()

        private fun pythonString(value: String): String = buildString {
            append('\'')
            for (ch in value) when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
            append('\'')
        }
    }
}
