package borg.trikeshed.hermes

import borg.trikeshed.lcnc.media.CausalMediaEmission
import borg.trikeshed.lcnc.media.LcncUserSignal
import borg.trikeshed.lcnc.media.ManualMediaInput
import borg.trikeshed.lcnc.media.MediaPatch
import borg.trikeshed.lcnc.media.MediaPatchPanelDescriptor
import borg.trikeshed.lcnc.media.MediaPatchPanelId
import borg.trikeshed.lcnc.media.MediaPatchPayload
import borg.trikeshed.lcnc.media.Vt220MediaPatchPanel
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.terminal.VtCell
import borg.trikeshed.terminal.VtKey
import borg.trikeshed.userspace.reactor.KanbanEvent
import borg.trikeshed.userspace.reactor.KanbanFSM
import borg.trikeshed.vm.Teleported
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A real VT220-backed UI session around the supervised no-native Hermes GraalPy sleeve. */
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

    val panel = Vt220MediaPatchPanel(
        MediaPatchPanelDescriptor(MediaPatchPanelId("hermes/vt220"), "vt220", "Hermes GraalPy", columns, rows),
    )
    private val lock = Any()
    private val currentCause = AtomicReference<String?>(null)
    private val _events = MutableSharedFlow<Event>(replay = 128, extraBufferCapacity = 1024)
    val events: SharedFlow<Event> = _events.asSharedFlow()
    @Volatile var state: State = State.CLOSED; private set
    @Volatile var detail: String = "not started"; private set
    @Volatile private var inventory: HermesPortInventory? = null
    private var stdoutEndedWithCr = false
    private var stderrEndedWithCr = false

    private val stdout = IncrementalUtf8OutputStream { text -> causalOutput(ttyNewlines(text, stderr = false), currentCause.get()) }
    private val stderr = IncrementalUtf8OutputStream { text -> causalOutput("\u001b[31m${ttyNewlines(text, stderr = true)}\u001b[0m", currentCause.get()) }
    private val port = HermesPythonPort(output = stdout, error = stderr)
    internal var inventoryLoader: (HermesPythonPort) -> HermesPortInventory = { p ->
        require(Files.isDirectory(root)) { "Hermes source root missing: $root" }
        p.inventory(root, sleeve.takeIf(Files::isDirectory))
    }

    fun open(timestampMs: Long = System.currentTimeMillis()): State {
        synchronized(lock) {
            if (state == State.READY || state == State.BOOTING) return state
            transition(State.BOOTING, "inventorying ${root.toAbsolutePath()}")
            causalOutput("\u001b[2J\u001b[H\u001b[1;38;5;208mTrikeShed Hermes VM · VT220\u001b[0m\r\n", null, timestampMs)
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
            port.evalInVm(CONSOLE_BOOTSTRAP, "hermes-vt220-bootstrap.py")
            transition(State.READY, "hermes_cli.main imported in no-native GraalPy")
            causalOutput("\u001b[32mready\u001b[0m · :help for console commands\r\n\u001b[38;5;208mhermes>\u001b[0m ", null, timestampMs)
            State.READY
        } catch (t: Throwable) {
            transition(State.FAILED, t.message ?: t.toString())
            causalOutput("\u001b[31mboot failed: ${t.message ?: t}\u001b[0m\r\n", null, timestampMs)
            State.FAILED
        }
    }

    /** Complete line submission. Web/TUI/desktop line disciplines all converge here. */
    fun submit(command: String, timestampMs: Long = System.currentTimeMillis()): LcncUserSignal =
        execute(prepareCommand(command, timestampMs), timestampMs)

    fun prepareCommand(command: String, timestampMs: Long = System.currentTimeMillis()): ManualMediaInput = synchronized(lock) {
        require(command.length <= Vt220MediaPatchPanel.MAX_SIGNAL_PAYLOAD) { "command exceeds ${Vt220MediaPatchPanel.MAX_SIGNAL_PAYLOAD} characters" }
        panel.manualCommand(command, timestampMs).also(::emitManual)
    }

    fun execute(manual: ManualMediaInput, timestampMs: Long = System.currentTimeMillis()): LcncUserSignal {
        val command = manual.signal.payload
        currentCause.set(manual.signal.id)
        causalOutput(command + "\r\n", manual.signal.id, timestampMs)
        if (state == State.CLOSED) open(timestampMs)
        if (state != State.READY) {
            causalOutput("\u001b[31mconsole unavailable: $detail\u001b[0m\r\n", manual.signal.id, timestampMs)
            currentCause.set(null)
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
            causalOutput("\u001b[38;5;208mhermes>\u001b[0m ", manual.signal.id, timestampMs)
            currentCause.set(null)
        }
        return manual.signal
    }

    fun reject(manual: ManualMediaInput, detail: String, timestampMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        emitCausal(panel.causalFailed(manual.signal.id, timestampMs, detail))
    }

    fun manualText(text: String, timestampMs: Long = System.currentTimeMillis(), paste: Boolean = false): ManualMediaInput = synchronized(lock) {
        panel.manualText(text, timestampMs, paste).also(::emitManual)
    }

    fun manualKey(
        key: VtKey,
        timestampMs: Long = System.currentTimeMillis(),
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): ManualMediaInput = synchronized(lock) {
        panel.manualKey(key, timestampMs, ctrl, alt, shift).also(::emitManual)
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

    /** A real PTY defaults to OPOST+ONLCR; Graal OutputStream does not, so supply that waist. */
    private fun ttyNewlines(text: String, stderr: Boolean): String = synchronized(lock) {
        var previousCr = if (stderr) stderrEndedWithCr else stdoutEndedWithCr
        val normalized = buildString(text.length + 8) {
            for (ch in text) {
                if (ch == '\n' && !previousCr) append('\r')
                append(ch)
                previousCr = ch == '\r'
            }
        }
        if (stderr) stderrEndedWithCr = previousCr else stdoutEndedWithCr = previousCr
        normalized
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
        runCatching { port.blackboard().put("hermes/console/signal/${signal.id}", map, "vt220") }
        KanbanFSM.kanbanEvents.tryEmit(signal.toKanbanEvent())
    }

    override fun close() = synchronized(lock) {
        port.close(); stdout.close(); stderr.close(); transition(State.CLOSED, "closed")
    }

    companion object {
        private val MODULE = Regex("[A-Za-z_][A-Za-z0-9_.]*")
        private val HELP = """
            \u001b[1m:status\u001b[0m        VM and sleeve state
            \u001b[1m:modules\u001b[0m       first 200 module dispositions
            \u001b[1m:gaps\u001b[0m          ranked native dependency gaps
            \u001b[1m:eval PYTHON\u001b[0m   evaluate inside the supervised GraalPy context
            \u001b[1m:import MODULE\u001b[0m  import one module through the sleeve
            \u001b[1m:hermes PROMPT\u001b[0m run a real Hermes one-shot turn
            \u001b[1m:clear\u001b[0m         reset the VT220 viewport
            Any other line is a Hermes prompt.
        """.trimIndent() + "\r\n"
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

fun LcncUserSignal.toMap(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "panelId" to panelId.value,
    "sequence" to sequence,
    "timestampMs" to timestampMs,
    "lane" to lane.name.lowercase(),
    "kind" to kind,
    "payload" to payload,
    "causeSignalId" to causeSignalId,
)

fun LcncUserSignal.toKanbanEvent(): KanbanEvent.LcncUserSignaled = KanbanEvent.LcncUserSignaled(
    panelId = panelId.value,
    signalId = id,
    lane = lane.name.lowercase(),
    kind = kind,
    causeSignalId = causeSignalId,
    payload = payload.take(512),
    timestampMs = timestampMs,
)

fun MediaPatch.toMap(): Map<String, Any?> = linkedMapOf(
    "panelId" to panelId.value,
    "kind" to kind.name.lowercase(),
    "revision" to revision,
    "x" to x,
    "y" to y,
    "width" to width,
    "height" to height,
    "causeSignalId" to causeSignalId,
    "cells" to ((payload as? MediaPatchPayload.TerminalCells)?.cells?.view?.map(VtCell::toMap) ?: emptyList()),
)

fun VtCell.toMap(): Map<String, Any?> = linkedMapOf(
    "text" to text,
    "continuation" to continuation,
    "fg" to style.foreground.index,
    "fgRgb" to style.foreground.rgb,
    "bg" to style.background.index,
    "bgRgb" to style.background.rgb,
    "bold" to style.bold,
    "faint" to style.faint,
    "italic" to style.italic,
    "underline" to style.underline,
    "blink" to style.blink,
    "inverse" to style.inverse,
    "concealed" to style.concealed,
    "crossedOut" to style.crossedOut,
)

/** Correctly preserves UTF-8 characters when Graal writes a multibyte sequence across calls. */
private class IncrementalUtf8OutputStream(private val sink: (String) -> Unit) : OutputStream() {
    private val pending = ByteArrayOutputStream()

    @Synchronized
    override fun write(value: Int) {
        pending.write(value)
        emitComplete()
    }

    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        pending.write(bytes, offset, length)
        emitComplete()
    }

    @Synchronized
    override fun flush() = emitComplete(force = true)

    @Synchronized
    override fun close() = emitComplete(force = true)

    private fun emitComplete(force: Boolean = false) {
        val bytes = pending.toByteArray()
        if (bytes.isEmpty()) return
        var index = 0
        var complete = 0
        while (index < bytes.size) {
            val unsigned = bytes[index].toInt() and 0xff
            val width = when {
                unsigned < 0x80 -> 1
                unsigned in 0xC2..0xDF -> 2
                unsigned in 0xE0..0xEF -> 3
                unsigned in 0xF0..0xF4 -> 4
                else -> 1
            }
            if (index + width > bytes.size) break
            complete = index + width
            index += width
        }
        if (force) complete = bytes.size
        if (complete == 0) return
        sink(bytes.copyOfRange(0, complete).toString(Charsets.UTF_8))
        pending.reset()
        if (complete < bytes.size) pending.write(bytes, complete, bytes.size - complete)
    }
}
