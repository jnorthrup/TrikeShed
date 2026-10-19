package borg.trikeshed.web.spacegraph

import borg.trikeshed.lcnc.*
import borg.trikeshed.web.*

/** One callback's immutable transport, reserved before it enters the page action lane. */
internal class PanelsAdmission(
    val canvas: dynamic,
    val source: dynamic,
    val sourceId: String?,
    val sourceRevision: Int,
    val name: String,
    val document: String,
    val program: LcncProgram,
    val baseReceipt: String?,
    val pulse: String?,
    val debug: Boolean,
    val live: Boolean,
)

internal fun Panels.reserveProgram(debug: Boolean = false, live: Boolean = false,
                                   pulse: dynamic = undefined, source: dynamic = null): PanelsAdmission? {
    if (page.closing) return null
    if (truthy(graph.controls?.inspectionOnly)) { status("Inspection-only construction; execution is disabled"); return null }
    if (programRunning || programAdmission != null || cases.busy) {
        status("Trigger not admitted while awaiting a receipt; it will not be replayed")
        return null
    }
    val name = str(panelName().value).ifBlank { "canvas" }
    val document: String
    val program: LcncProgram
    val frozenPulse: String?
    try {
        document = JSON.stringify(toConfix())
        program = LcncProgramConfix.fromJson(name, document)
        frozenPulse = if (pulse === undefined) null else JSON.stringify(pulse)
    } catch (failure: dynamic) { status("Program refused: " + failureText(failure)); return null }
    val base = pulseReceipt
    if (frozenPulse != null && (base == null || pulseProgram == null ||
            !LcncProgramConfix.sameDefinition(pulseProgram!!, program))) {
        stopSources()
        status("No completed receipt for this definition. Choose Run or Live first; this gesture was not replayed.")
        return null
    }
    return PanelsAdmission(graph, source, if (source == null) null else str(source.id), sourceRevision,
        name, document, program, base, frozenPulse, debug, live).also { programAdmission = it }
}

internal fun Panels.releaseProgram(admission: PanelsAdmission) {
    if (programAdmission === admission) programAdmission = null
}

internal fun Panels.currentProgram(admission: PanelsAdmission): Boolean {
    if (programAdmission !== admission || page.closing || programRunning || graph !== admission.canvas ||
        sourceRevision != admission.sourceRevision || pulseReceipt != admission.baseReceipt) return false
    if (admission.sourceId != null && (node(admission.sourceId) !== admission.source ||
        truthy(admission.source._parentScope) || !truthy(admission.source.el?.isConnected))) return false
    return try {
        val name = str(panelName().value).ifBlank { "canvas" }
        name == admission.name && LcncProgramConfix.sameDefinition(admission.program,
            LcncProgramConfix.fromJson(name, JSON.stringify(toConfix())))
    } catch (_: dynamic) { false }
}

internal fun Panels.queueProgram(admission: PanelsAdmission, done: ((dynamic) -> Unit)? = null) {
    var reported = false
    fun report(result: dynamic) {
        if (reported) return
        reported = true
        done?.invoke(result)
    }
    val completion = try { page.submit { report(runAdmittedProgram(admission)) } }
    catch (failure: dynamic) { releaseProgram(admission); report(null); throw failure }
    if (completion == null) {
        releaseProgram(admission)
        report(objOf { it.notAdmitted = "Page action capacity is full or closing; gesture was not admitted" })
    } else completion.invokeOnCompletion { failure ->
        releaseProgram(admission)
        if (failure != null) report(null)
    }
}

/** Toolbar Run/Live/Debug uses this directly in its DOM callback, not inside launchJs. */
internal fun Panels.admitProgram(debug: Boolean = false, live: Boolean = false) {
    reserveProgram(debug, live)?.let { queueProgram(it) }
}

/** Consume and snapshot the actual root observation synchronously; refused events cannot be revived. */
internal fun Panels.admitPulse(fromId: String) {
    val source = node(fromId) ?: return
    val press = source._pressedAt; source._pressedAt = null
    val move = source._lastMove; source._lastMove = null
    val event = source._lastEvent; source._lastEvent = null
    val ended = source._endedAt; source._endedAt = null
    if (truthy(source._parentScope)) {
        status("Nested controls require an invocation path; this gesture was not admitted")
        return
    }
    val outputs = obj()
    when (str(source.type)) {
        "timer" -> Unit // The daemon's clock still produces the actual tick.
        "button" -> { if (press == null) return; outputs.press = press }
        "slider" -> outputs.value = num(source._sval ?: p(source, "value") ?: 0)
        "dom.board" -> { if (move == null) return; outputs.move = move }
        "graal.events", "vm.events", "dom.events" -> { if (event == null) return; outputs.event = event }
        "media.player" -> {
            val media = source._media ?: return
            outputs.state = mediaState(media)
            if (ended != null) outputs.ended = ended
        }
        else -> { status("This node is not a root control. Choose Run to execute the authored program."); return }
    }
    val pulse = objOf { it.nodeId = fromId; it.outputs = outputs }
    reserveProgram(pulse = pulse, source = source)?.let { queueProgram(it) }
}
