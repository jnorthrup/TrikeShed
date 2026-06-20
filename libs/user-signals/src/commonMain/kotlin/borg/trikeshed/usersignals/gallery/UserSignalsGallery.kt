package borg.trikeshed.usersignals.gallery

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import borg.trikeshed.usersignals.Signal
import borg.trikeshed.usersignals.TextFieldState
import borg.trikeshed.usersignals.idiotLight
import borg.trikeshed.usersignals.knob
import borg.trikeshed.usersignals.levelMeter
import borg.trikeshed.usersignals.momentaryButton
import borg.trikeshed.usersignals.slider
import borg.trikeshed.usersignals.textField
import borg.trikeshed.usersignals.constSignal
import borg.trikeshed.usersignals.toggle
import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.round

@JvmInline
value class GalleryStageId(val value: String)

@JvmInline
value class GalleryTitle(val value: String)

@JvmInline
value class GalleryIntent(val value: String)

@JvmInline
value class GallerySignalId(val value: String)

@JvmInline
value class GalleryDrilldown(val value: String)

@JvmInline
value class Magnification(val value: Int)

typealias GalleryCardMeta = Join<GalleryStageId, Join<GalleryTitle, GalleryIntent>>
typealias GallerySignalMeta = Join<GallerySignalId, GalleryDrilldown>
typealias GallerySignalNode = Join<GallerySignalMeta, Signal<*>>
typealias GallerySignalSeries = Series<GallerySignalNode>
typealias UserSignalsGalleryCard = Join<GalleryCardMeta, GallerySignalSeries>
typealias UserSignalsGallery = Series<UserSignalsGalleryCard>

fun userSignalsGallery(): UserSignalsGallery =
    s_[
        galleryCard(
            stageId = "signals.foundation.0d",
            title = "0D signals",
            intent = "Boolean, momentary, and alert surfaces from user input",
            signals = s_[
                gallerySignal("intent.accepted", "toggle captures durable user consent", toggle(initial = true)),
                gallerySignal("attention.flash", "idiot light carries transient attention state", idiotLight(initial = false)),
                gallerySignal("command.press", "momentary button models edge-triggered user action", momentaryButton()),
            ],
        ),
        galleryCard(
            stageId = "signals.foundation.1d",
            title = "1D signals",
            intent = "Scalar controls expose continuous user preference and telemetry",
            signals = s_[
                gallerySignal("budget.slider", "slider normalizes user budget into a bounded scalar", slider(0.0, 1.0, 0.64, 0.01)),
                gallerySignal("focus.knob", "knob rotates priority into a cyclic control surface", knob(0.0, 1.0, 0.42, detents = 12)),
                gallerySignal("confidence.level", "level meter carries observed signal strength", levelMeter().apply { setLevel(0.78) }),
            ],
        ),
        galleryCard(
            stageId = "signals.foundation.text",
            title = "Text signals",
            intent = "Text field preserves caret and commitment details for low-code/no-code entry",
            signals = s_[
                gallerySignal("prompt.text", "text field retains typed user signal plus cursor state", textField("open kanban node details").apply {
                    focus()
                    setSelection(5, 11)
                    commit()
                }),
                gallerySignal("section.note", "constant signal anchors gallery context", Signal.Const("user-signals gallery")),
            ],
        ),
    ]

fun renderUserSignalsGallery(
    gallery: UserSignalsGallery = userSignalsGallery(),
    magnification: Magnification = Magnification(1),
): String {
    val zoom = magnification.value.coerceAtLeast(1)
    val text = StringBuilder()
    text.appendLine("user-signals gallery")
    for (cardIndex in 0 until gallery.size) {
        val card = gallery[cardIndex]
        val meta = card.a
        val stageId = meta.a.value
        val title = meta.b.a.value
        val intent = meta.b.b.value
        text.appendLine()
        text.appendLine("$stageId :: $title")
        if (zoom >= 2) text.appendLine("  $intent")
        appendSignalRows(text, card.b, zoom)
    }
    return text.toString()
}

fun main() {
    println(renderUserSignalsGallery(magnification = Magnification(3)))
}

private fun galleryCard(
    stageId: String,
    title: String,
    intent: String,
    signals: GallerySignalSeries,
): UserSignalsGalleryCard =
    (GalleryStageId(stageId) j (GalleryTitle(title) j GalleryIntent(intent))) j signals

private fun gallerySignal(
    id: String,
    drilldown: String,
    signal: Signal<*>,
): GallerySignalNode =
    (GallerySignalId(id) j GalleryDrilldown(drilldown)) j signal

private fun appendSignalRows(
    text: StringBuilder,
    signals: GallerySignalSeries,
    zoom: Int,
) {
    for (signalIndex in 0 until signals.size) {
        val node = signals[signalIndex]
        val signalId = node.a.a.value
        val drilldown = node.a.b.value
        val signal = node.b
        text.append("  - ")
        text.append(signalId)
        text.append(" = ")
        text.append(renderSignalValue(signal.value))
        if (zoom >= 2) {
            text.append(" | ")
            text.append(drilldown)
        }
        if (zoom >= 3) {
            text.append(" | type=")
            text.append(renderSignalType(signal.value))
        }
        text.appendLine()
    }
}

private fun renderSignalValue(value: Any?): String = when (value) {
    is Boolean -> if (value) "on" else "off"
    is Double -> value.toFixed(2)
    is TextFieldState -> buildString {
        append("\"")
        append(value.text)
        append("\" caret=")
        append(value.caret)
        if (value.hasSelection) {
            append(" selection=")
            append(value.selectionStart)
            append("..")
            append(value.selectionEnd)
            append(" selected=\"")
            append(value.selectedText)
            append("\"")
        }
        append(" focused=")
        append(value.focused)
        append(" committed=")
        append(value.committed)
    }
    null -> "null"
    else -> value.toString()
}

private fun renderSignalType(value: Any?): String = when (value) {
    is Boolean -> "Boolean"
    is Double -> "Double"
    is TextFieldState -> "TextFieldState"
    is String -> "String"
    null -> "Null"
    else -> value::class.simpleName ?: "Any"
}

private fun Double.toFixed(decimals: Int): String {
    val scale = (1..decimals).fold(1L) { acc, _ -> acc * 10L }
    val scaled = round(abs(this) * scale).toLong()
    val whole = scaled / scale
    val fraction = (scaled % scale).toString().padStart(decimals, '0')
    val sign = if (this < 0) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole.$fraction"
}
