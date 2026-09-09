package borg.trikeshed.hermes

import borg.trikeshed.lcnc.media.CausalMediaEmission
import borg.trikeshed.lcnc.media.LcncUserSignal
import borg.trikeshed.lcnc.media.ManualMediaInput
import borg.trikeshed.lcnc.media.MediaPatchPanelDescriptor
import borg.trikeshed.lcnc.media.MediaPatchPanelId
import borg.trikeshed.lcnc.media.XtermMediaPatchPanel
import borg.trikeshed.lcnc.media.toKanbanEvent
import borg.trikeshed.lcnc.media.toMap
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.terminal.VtCell
import borg.trikeshed.terminal.VtKey
import borg.trikeshed.terminal.TerminalOutputStream
import borg.trikeshed.userspace.reactor.KanbanFSM
import borg.trikeshed.vm.Teleported

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A real xterm-256color session around the supervised no-native Hermes GraalPy sleeve. */
class HermesVmConsole(
    private val root: Path,
    private val sleeve: Path,
    columns: Int = 100,
    rows: Int = 32,
) : AutoCloseable {
    enum class State { CLOSED, BOOTING, READY, FAILED }

    sealed class Event {
        data class Manual(val signal: LcncUserSignal) : Event()
        data class Causal(val emission: CausalMediaEmission) : Event()
        data class StateChanged(val state: State, val detail: String) : Event()
    }

    val panel = XtermMediaPatchPanel(
        MediaPatchPanelDescriptor(MediaPatchPanelId("hermes/xterm"), "xterm-256color", "Hermes GraalPy", columns, rows),
    )
    private val lock = Any()
    private val currentCause = AtomicReference<String?>(null)

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
    private val _events = MutableSharedFlow<Event>(replay = 128, extraBufferCapacity = 1024)
    val events: SharedFlow<Event> = _events.asSharedFlow()
    @Volatile var state: State = State.CLOSED; private set
    @Volatile var detail: String = "not started"; private set
    @Volatile private var inventory: HermesPortInventory? = null
    private val stdout = TerminalOutputStream { text -> causalOutput(text, currentCause.get()) }
    private val stderr = TerminalOutputStream { text -> causalOutput("\u001b[31m$text\u001b[0m", currentCause.get()) }
    private val port = HermesPythonPort(output = stdout, error = stderr)
    internal var inventoryLoader: (HermesPythonPort) -> HermesPortInventory = { p ->
        require(Files.isDirectory(root)) { "Hermes source root missing: $root" }
        p.inventory(root, sleeve.takeIf(Files::isDirectory))
    }

    fun open(timestampMs: Long = System.currentTimeMillis()): State {
        synchronized(lock) {
            if (state == State.READY || state == State.BOOTING) return state
            transition(State.BOOTING, "inventorying ${root.toAbsolutePath()}")
            causalOutput("\u001b[2J\u001b[H\u001b[1;38;5;208mTrikeShed Hermes VM · xterm-256color\u001b[0m\r\n", null, timestampMs)
        }
        return try {
            val scanned = inventoryLoader(port)
            inventory = scanned
            causalOutput(
                "inventory ${scanned.modules.size} modules · ${scanned.ready} ready · " +
                    "${scanned.blockedNative} native-blocked · ${scanned.blockedTransitive} transitive\r\n",
                null,
                timestampMs,
            )
            port.importInVm(scanned, "hermes_cli.main")
            port.evalInVm(CONSOLE_BOOTSTRAP, "hermes-xterm-bootstrap.py")
            transition(State.READY, "hermes_cli.main imported in no-native GraalPy")
            causalOutput("\u001b[32mready\u001b[0m · :help for console commands\r\n", null, timestampMs)
            if (!turnInFlight) promptAndRedraw(timestampMs)
            State.READY
        } catch (t: Throwable) {
            transition(State.FAILED, t.message ?: t.toString())
            causalOutput("\u001b[31mboot failed: ${t.message ?: t}\u001b[0m\r\n", null, timestampMs)
            if (!turnInFlight) promptAndRedraw(timestampMs)
            State.FAILED
        }
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
        windowStart = 0
        redrawLine(timestampMs)
    }

    fun resize(columns: Int, rows: Int, timestampMs: Long = System.currentTimeMillis()): CausalMediaEmission = synchronized(lock) {
        panel.manualResize(columns, rows, timestampMs).also(::emitCausal)
    }

    fun snapshotMap(): Map<String, Any?> = synchronized(lock) {
        val snapshot = panel.snapshot()
        val scanned = inventory
        linkedMapOf(
            "state" to state.name.lowercase(),
            "detail" to detail,
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
                    "state=${state.name.lowercase()} vm=${port.vmStarted} modules=${scanned?.modules?.size ?: 0} " +
                        "ready=${scanned?.ready ?: 0} blocked=${(scanned?.blockedNative ?: 0) + (scanned?.blockedTransitive ?: 0)}\r\n",
                    cause,
                    timestampMs,
                )
            }
            command == ":clear" -> causalOutput("\u001b[2J\u001b[H", cause, timestampMs)
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
        val result = port.evalInVm("__trikeshed_hermes(${pythonString(prompt)})", "hermes-console-turn.py")
        if ((result as? Teleported.Num)?.v != 0L) causalOutput("\r\nhermes exit $result\r\n", currentCause.get())
    }

    private fun causalOutput(text: String, cause: String?, timestampMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (text.isEmpty()) return@synchronized
        emitCausal(panel.causalOutput(text, cause, timestampMs))
    }


    private fun emitManual(input: ManualMediaInput) {
        land(input.signal)
        _events.tryEmit(Event.Manual(input.signal))
    }

    private fun emitCausal(emission: CausalMediaEmission) {
        land(emission.signal)
        _events.tryEmit(Event.Causal(emission))
    }

    private fun transition(next: State, value: String) {
        state = next; detail = value
        _events.tryEmit(Event.StateChanged(next, value))
    }

    private fun land(signal: LcncUserSignal) {
        val map = signal.toMap()
        runCatching { port.blackboard().put("hermes/console/signal/${signal.id}", map, "xterm") }
        KanbanFSM.kanbanEvents.tryEmit(signal.toKanbanEvent())
    }

    override fun close() = synchronized(lock) {
        port.close(); stdout.close(); stderr.close(); transition(State.CLOSED, "closed")
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
            "${ESC}[1m:clear${ESC}[0m         reset the viewport      \r\n" +
            "Any other line is a Hermes prompt.\r\n" +
            "${ESC}[1m:env${ESC}[0m            curated guest environment (diagnostic)\r\n"
        private val CONSOLE_BOOTSTRAP = """
            def __trikeshed_hermes(prompt):
                from hermes_cli.oneshot import run_oneshot
                return run_oneshot(prompt)
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
