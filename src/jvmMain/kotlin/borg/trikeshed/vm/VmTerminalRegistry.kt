package borg.trikeshed.vm

import borg.trikeshed.lcnc.media.CausalMediaEmission
import borg.trikeshed.lcnc.media.LcncUserSignal
import borg.trikeshed.lcnc.media.ManualMediaInput
import borg.trikeshed.lcnc.media.MediaPatchPanelDescriptor
import borg.trikeshed.lcnc.media.MediaPatchPanelId
import borg.trikeshed.lcnc.media.XtermMediaPatchPanel
import borg.trikeshed.lcnc.media.toKanbanEvent
import borg.trikeshed.lcnc.media.toMap
import borg.trikeshed.lib.view
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.terminal.TerminalInputStream
import borg.trikeshed.terminal.TerminalOutputStream
import borg.trikeshed.terminal.VtCell
import borg.trikeshed.userspace.reactor.KanbanFSM
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class VmTerminalEvent {
    abstract val vmId: String
    data class Manual(override val vmId: String, val signal: LcncUserSignal) : VmTerminalEvent()
    data class Causal(override val vmId: String, val emission: CausalMediaEmission) : VmTerminalEvent()
    data class Phase(override val vmId: String, val phase: String, val detail: String) : VmTerminalEvent()
}

/** One process/VM, one terminal, one manual→causal signal lineage. */
class VmTerminalSession(
    val vmId: String,
    val facet: VmFacet,
    val tier: String,
    columns: Int = 100,
    rows: Int = 28,
    private val publish: (VmTerminalEvent) -> Unit = {},
) : AutoCloseable {
    val panel = XtermMediaPatchPanel(
        MediaPatchPanelDescriptor(MediaPatchPanelId("vm/$vmId/xterm"), "xterm-256color", "$vmId · ${facet.id}", columns, rows),
    )
    val input = TerminalInputStream()
    @Volatile private var inputSink: (String) -> Unit = input::push
    private val cause = AtomicReference<String?>(null)
    private val lock = Any()
    private val _events = MutableSharedFlow<VmTerminalEvent>(replay = 128, extraBufferCapacity = 1024)
    val events: SharedFlow<VmTerminalEvent> = _events.asSharedFlow()
    @Volatile var phase: String = "live"; private set
    @Volatile var detail: String = ""; private set

    val output = TerminalOutputStream { text -> output(text, cause.get()) }
    val error = TerminalOutputStream { text -> output("\u001b[31m$text\u001b[0m", cause.get()) }

    init {
        panel.terminal.drainPatches()
        output("\u001b[1;38;5;208m$vmId\u001b[0m · ${facet.id} · $tier\r\n", null)
        output(prompt(), null)
    }

    fun prepare(command: String, timestampMs: Long = System.currentTimeMillis()): ManualMediaInput = synchronized(lock) {
        require(command.length <= XtermMediaPatchPanel.MAX_SIGNAL_PAYLOAD) { "command too long" }
        panel.manualCommand(command, timestampMs).also { emit(VmTerminalEvent.Manual(vmId, it.signal)); land(it.signal) }
    }

    fun begin(input: ManualMediaInput, timestampMs: Long = System.currentTimeMillis()) {
        cause.set(input.signal.id)
        output(input.signal.payload + "\r\n", input.signal.id, timestampMs)
    }

    fun complete(value: Teleported, input: ManualMediaInput, timestampMs: Long = System.currentTimeMillis()) {
        if (value != Teleported.Null) output("$value\r\n", input.signal.id, timestampMs)
        emitCausal(panel.causalCompleted(input.signal.id, timestampMs, value.cid.value))
        output(prompt(), input.signal.id, timestampMs)
        cause.compareAndSet(input.signal.id, null)
    }

    fun fail(error: Throwable, input: ManualMediaInput, timestampMs: Long = System.currentTimeMillis()) {
        val message = error.message ?: error.toString()
        output("\u001b[31m$message\u001b[0m\r\n", input.signal.id, timestampMs)
        emitCausal(panel.causalFailed(input.signal.id, timestampMs, message))
        output(prompt(), input.signal.id, timestampMs)
        cause.compareAndSet(input.signal.id, null)
    }

    fun pushInput(text: String, timestampMs: Long = System.currentTimeMillis()): ManualMediaInput = synchronized(lock) {
        val manual = panel.manualText(text, timestampMs, paste = true)
        emit(VmTerminalEvent.Manual(vmId, manual.signal)); land(manual.signal)
        cause.set(manual.signal.id)
        inputSink(manual.input)
        manual
    }

    fun bindInput(sink: (String) -> Unit) { inputSink = sink }

    fun resize(columns: Int, rows: Int, timestampMs: Long = System.currentTimeMillis()): CausalMediaEmission = synchronized(lock) {
        panel.manualResize(columns, rows, timestampMs).also(::emitCausal)
    }

    fun setPhase(next: String, value: String = "") {
        phase = next; detail = value
        emit(VmTerminalEvent.Phase(vmId, next, value))
    }

    fun systemOutput(text: String, causeSignalId: String? = null, timestampMs: Long = System.currentTimeMillis()) {
        output(text, causeSignalId, timestampMs)
    }

    fun snapshotMap(): Map<String, Any?> = synchronized(lock) {
        val snapshot = panel.snapshot()
        linkedMapOf(
            "vmId" to vmId,
            "facet" to facet.id,
            "tier" to tier,
            "phase" to phase,
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

    fun summaryMap(): Map<String, Any?> = synchronized(lock) {
        val snapshot = panel.snapshot(scrollbackRows = 0)
        mapOf(
            "vmId" to vmId,
            "facet" to facet.id,
            "tier" to tier,
            "phase" to phase,
            "detail" to detail,
            "columns" to snapshot.columns,
            "rows" to snapshot.rows,
            "revision" to snapshot.revision,
        )
    }

    private fun output(text: String, causeId: String?, timestampMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (text.isNotEmpty()) emitCausal(panel.causalOutput(text, causeId, timestampMs))
    }

    private fun emitCausal(emission: CausalMediaEmission) {
        land(emission.signal)
        emit(VmTerminalEvent.Causal(vmId, emission))
    }

    private fun emit(event: VmTerminalEvent) {
        _events.tryEmit(event)
        publish(event)
    }

    private fun land(signal: LcncUserSignal) {
        KanbanFSM.kanbanEvents.tryEmit(signal.toKanbanEvent())
    }

    private fun prompt(): String = "\u001b[38;5;208m$vmId>\u001b[0m "

    fun close(detail: String) {
        input.close(); output.close(); error.close(); setPhase("closed", detail)
    }

    override fun close() = close("closed")
}

/** Process-wide terminal index. Sessions remain readable after close for postmortem inspection. */
class VmTerminalRegistry : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, VmTerminalSession>()
    private val _events = MutableSharedFlow<VmTerminalEvent>(replay = 256, extraBufferCapacity = 2048)
    val events: SharedFlow<VmTerminalEvent> = _events.asSharedFlow()

    fun open(id: String, facet: VmFacet, tier: String): VmTerminalSession =
        sessions.computeIfAbsent(id) { VmTerminalSession(id, facet, tier, publish = { event -> _events.tryEmit(event) }) }

    operator fun get(id: String): VmTerminalSession? = sessions[id]
    fun ids(): List<String> = sessions.keys.sorted()
    fun snapshots(): List<Map<String, Any?>> = ids().mapNotNull { sessions[it]?.summaryMap() }

    fun close(id: String, detail: String = "closed") {
        sessions[id]?.close(detail)
    }

    override fun close() { for (id in ids()) close(id) }
}
