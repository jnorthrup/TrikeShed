package borg.trikeshed.lcnc.media

import borg.trikeshed.context.lcnc.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.terminal.*
import kotlin.jvm.JvmInline

@JvmInline
value class MediaPatchPanelId(val value: String)

enum class MediaPatchKind { TERMINAL_CELLS, TEXT, IMAGE_REGION, AUDIO_RANGE, VIDEO_FRAME }
enum class LcncSignalLane { MANUAL, CAUSAL }
enum class ManualSignalKind { TEXT, KEY, PASTE, RESIZE, FOCUS, COMMAND }
enum class CausalSignalKind { OUTPUT, PATCH, TITLE, BELL, COMPLETED, FAILED }

data class MediaPatchPanelDescriptor(
    val id: MediaPatchPanelId,
    val kind: String,
    val title: String,
    val columns: Int,
    val rows: Int,
)

sealed class MediaPatchPayload {
    data class TerminalCells(val cells: Series<VtCell>) : MediaPatchPayload()
    data class Text(val value: String) : MediaPatchPayload()
    data class Bytes(val contentType: String, val bytes: ByteArray) : MediaPatchPayload()
}

data class MediaPatch(
    val panelId: MediaPatchPanelId,
    val kind: MediaPatchKind,
    val revision: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val payload: MediaPatchPayload,
    val causeSignalId: String?,
) {
    companion object
}

/** One user-originated or consequence-originated LCNC signal with an explicit causal parent. */
data class LcncUserSignal(
    val id: String,
    val panelId: MediaPatchPanelId,
    val sequence: Long,
    val timestampMs: Long,
    val lane: LcncSignalLane,
    val kind: String,
    val payload: String,
    val causeSignalId: String? = null,
) {
    companion object

    /** LCNC's packed marker spine: manual ingress is Inducted; consequence is Answered. */
    fun marked(): MarkedResult<LcncUserSignal> = marked(
        this,
        facet = FacetMark.WtkHint,
        causal = if (lane == LcncSignalLane.MANUAL) CausalMark.Inducted else CausalMark.Answered,
        pointcut = PointcutMark.AfterSet,
    )
}

data class ManualMediaInput(
    val signal: LcncUserSignal,
    /** Exact terminal input bytes represented as a String; the transport chooses UTF-8 encoding. */
    val input: String,
)

data class CausalMediaEmission(
    val signal: LcncUserSignal,
    val patches: Series<MediaPatch>,
)

/**
 * The terminal media patch panel. The caller owns this mutable state and decides where signals are
 * persisted or fanned out; the panel only establishes manual→causal identity and terminal patches.
 */
class XtermMediaPatchPanel(
    val descriptor: MediaPatchPanelDescriptor,
    scrollbackLimit: Int = 2_000,
    private val signalLimit: Int = 4_096,
) {
    val terminal = XtermTerminal(descriptor.columns, descriptor.rows, scrollbackLimit)
    private var sequence = 0L
    private val history = ArrayDeque<LcncUserSignal>()

    init {
        // "xterm" is accepted for continuity with panels created before the capability upgrade;
        // "xterm-256color" is what new panels declare — real VT220 hardware never had ANSI SGR
        // color, and this parser's `sgr()` handles 16/256/truecolor regardless of which label a
        // caller uses, so self-reporting the lesser class only made well-behaved clients downgrade.
        require(descriptor.kind == "xterm" || descriptor.kind == "xterm-256color") {
            "unsupported panel kind: ${descriptor.kind}"
        }
        require(signalLimit > 0)
    }

    fun manualText(text: String, timestampMs: Long, paste: Boolean = false): ManualMediaInput =
        ManualMediaInput(manual(if (paste) ManualSignalKind.PASTE else ManualSignalKind.TEXT, text, timestampMs), terminal.encodeText(text))

    fun manualCommand(command: String, timestampMs: Long): ManualMediaInput =
        ManualMediaInput(manual(ManualSignalKind.COMMAND, command, timestampMs), terminal.encodeText(command) + "\r")

    fun manualKey(
        key: VtKey,
        timestampMs: Long,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): ManualMediaInput {
        val payload = buildString {
            append(key.name)
            if (ctrl) append("+CTRL")
            if (alt) append("+ALT")
            if (shift) append("+SHIFT")
        }
        return ManualMediaInput(manual(ManualSignalKind.KEY, payload, timestampMs), terminal.encode(key, ctrl, alt, shift))
    }

    fun manualResize(columns: Int, rows: Int, timestampMs: Long): CausalMediaEmission {
        val manual = manual(ManualSignalKind.RESIZE, "$columns×$rows", timestampMs)
        val patches = terminal.resize(columns, rows)
        return causal(CausalSignalKind.PATCH, "resize $columns×$rows", manual.id, timestampMs, patches)
    }

    fun causalOutput(text: String, causeSignalId: String?, timestampMs: Long): CausalMediaEmission {
        val vtPatches = terminal.feed(text, causeSignalId)
        val kind = when {
            text.indexOf('\u0007') >= 0 -> CausalSignalKind.BELL
            terminal.title.isNotEmpty() && vtPatches.size == 0 -> CausalSignalKind.TITLE
            else -> CausalSignalKind.OUTPUT
        }
        return causal(kind, text, causeSignalId, timestampMs, vtPatches)
    }

    fun causalCompleted(causeSignalId: String?, timestampMs: Long, detail: String = "ok"): CausalMediaEmission =
        causal(CausalSignalKind.COMPLETED, detail, causeSignalId, timestampMs, emptySeriesOf())

    fun causalFailed(causeSignalId: String?, timestampMs: Long, detail: String): CausalMediaEmission =
        causal(CausalSignalKind.FAILED, detail, causeSignalId, timestampMs, emptySeriesOf())

    fun causalText(text: String, causeSignalId: String?, timestampMs: Long): CausalMediaEmission {
        val signal = signal(LcncSignalLane.CAUSAL, CausalSignalKind.OUTPUT.name.lowercase(), text, causeSignalId, timestampMs)
        val patch = MediaPatch(
            panelId = descriptor.id,
            kind = MediaPatchKind.TEXT,
            revision = signal.sequence,
            x = 0,
            y = 0,
            width = text.length,
            height = 1,
            payload = MediaPatchPayload.Text(text),
            causeSignalId = causeSignalId,
        )
        return CausalMediaEmission(signal, s_[patch])
    }

    fun causalBytes(contentType: String, bytes: ByteArray, causeSignalId: String?, timestampMs: Long): CausalMediaEmission {
        val signal = signal(LcncSignalLane.CAUSAL, CausalSignalKind.PATCH.name.lowercase(), "$contentType (${bytes.size} bytes)", causeSignalId, timestampMs)
        val patch = MediaPatch(
            panelId = descriptor.id,
            kind = MediaPatchKind.IMAGE_REGION,
            revision = signal.sequence,
            x = 0,
            y = 0,
            width = bytes.size,
            height = 1,
            payload = MediaPatchPayload.Bytes(contentType, bytes),
            causeSignalId = causeSignalId,
        )
        return CausalMediaEmission(signal, s_[patch])
    }

    fun snapshot(scrollbackRows: Int = 200): VtSnapshot = terminal.snapshot(scrollbackRows)

    fun signals(): Series<LcncUserSignal> {
        val copy = history.toList()
        return copy.size j { i: Int -> copy[i] }
    }

    private fun manual(kind: ManualSignalKind, payload: String, timestampMs: Long): LcncUserSignal =
        signal(LcncSignalLane.MANUAL, kind.name.lowercase(), payload, null, timestampMs)

    private fun causal(
        kind: CausalSignalKind,
        payload: String,
        causeSignalId: String?,
        timestampMs: Long,
        patches: Series<VtPatch>,
    ): CausalMediaEmission {
        val signal = signal(LcncSignalLane.CAUSAL, kind.name.lowercase(), payload, causeSignalId, timestampMs)
        val projected = patches.size j { i: Int ->
            val patch = patches[i]
            MediaPatch(
                panelId = descriptor.id,
                kind = MediaPatchKind.TERMINAL_CELLS,
                revision = patch.revision,
                x = patch.column,
                y = patch.row,
                width = patch.cells.size,
                height = 1,
                payload = MediaPatchPayload.TerminalCells(patch.cells),
                causeSignalId = causeSignalId,
            )
        }
        return CausalMediaEmission(signal, projected)
    }

    private fun signal(
        lane: LcncSignalLane,
        kind: String,
        payload: String,
        causeSignalId: String?,
        timestampMs: Long,
    ): LcncUserSignal {
        val next = ++sequence
        val canonical = "${descriptor.id.value}|$next|${lane.name}|$kind|${causeSignalId.orEmpty()}|$payload"
        val signal = LcncUserSignal(
            id = ContentId.of(canonical.encodeToByteArray()).value,
            panelId = descriptor.id,
            sequence = next,
            timestampMs = timestampMs,
            lane = lane,
            kind = kind,
            payload = payload.take(MAX_SIGNAL_PAYLOAD),
            causeSignalId = causeSignalId,
        )
        history.addLast(signal)
        while (history.size > signalLimit) history.removeFirst()
        return signal
    }

    companion object {
        const val MAX_SIGNAL_PAYLOAD: Int = 4_096
    }
}
