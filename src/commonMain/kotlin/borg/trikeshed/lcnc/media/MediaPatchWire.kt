package borg.trikeshed.lcnc.media

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import borg.trikeshed.terminal.VtCell
import borg.trikeshed.terminal.VtColor
import borg.trikeshed.terminal.VtStyle
import borg.trikeshed.userspace.reactor.KanbanEvent

/** Stable JSON/wire projections shared by Hermes and every other terminal panel. */
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

fun LcncUserSignal.Companion.fromMap(map: Map<String, Any?>): LcncUserSignal? {
    val id = map["id"]?.toString() ?: return null
    val panelId = map["panelId"]?.toString() ?: return null
    val sequence = (map["sequence"] as? Number)?.toLong() ?: 0L
    val timestampMs = (map["timestampMs"] as? Number)?.toLong() ?: 0L
    val laneStr = map["lane"]?.toString()?.uppercase() ?: "MANUAL"
    val lane = runCatching { LcncSignalLane.valueOf(laneStr) }.getOrDefault(LcncSignalLane.MANUAL)
    val kind = map["kind"]?.toString() ?: "text"
    val payload = map["payload"]?.toString() ?: ""
    val causeSignalId = map["causeSignalId"]?.toString()
    return LcncUserSignal(
        id = id,
        panelId = MediaPatchPanelId(panelId),
        sequence = sequence,
        timestampMs = timestampMs,
        lane = lane,
        kind = kind,
        payload = payload,
        causeSignalId = causeSignalId,
    )
}

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
    "text" to (payload as? MediaPatchPayload.Text)?.value,
    "contentType" to (payload as? MediaPatchPayload.Bytes)?.contentType,
    "bytes" to (payload as? MediaPatchPayload.Bytes)?.bytes,
)

fun MediaPatch.Companion.fromMap(map: Map<String, Any?>): MediaPatch? {
    val panelId = map["panelId"]?.toString() ?: return null
    val kindStr = map["kind"]?.toString()?.uppercase() ?: "TERMINAL_CELLS"
    val kind = runCatching { MediaPatchKind.valueOf(kindStr) }.getOrDefault(MediaPatchKind.TERMINAL_CELLS)
    val revision = (map["revision"] as? Number)?.toLong() ?: 0L
    val x = (map["x"] as? Number)?.toInt() ?: 0
    val y = (map["y"] as? Number)?.toInt() ?: 0
    val width = (map["width"] as? Number)?.toInt() ?: 0
    val height = (map["height"] as? Number)?.toInt() ?: 0
    val causeSignalId = map["causeSignalId"]?.toString()

    val payload: MediaPatchPayload = when {
        map["text"] != null -> {
            MediaPatchPayload.Text(map["text"].toString())
        }
        map["bytes"] != null -> {
            val bytes = when (val b = map["bytes"]) {
                is ByteArray -> b
                is List<*> -> ByteArray(b.size) { i -> (b[i] as? Number)?.toByte() ?: 0 }
                else -> ByteArray(0)
            }
            val contentType = map["contentType"]?.toString() ?: "application/octet-stream"
            MediaPatchPayload.Bytes(contentType, bytes)
        }
        map.containsKey("cells") -> {
            val rawCells = (map["cells"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
            val cellsList = rawCells.map { cellMap -> VtCell.fromMap(cellMap) }
            MediaPatchPayload.TerminalCells(cellsList.toSeries())
        }
        else -> MediaPatchPayload.Text("")
    }

    return MediaPatch(
        panelId = MediaPatchPanelId(panelId),
        kind = kind,
        revision = revision,
        x = x,
        y = y,
        width = width,
        height = height,
        payload = payload,
        causeSignalId = causeSignalId,
    )
}

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

fun VtCell.Companion.fromMap(map: Map<String, Any?>): VtCell {
    val text = map["text"]?.toString() ?: " "
    val cont = map["continuation"] == true
    val fgIdx = (map["fg"] as? Number)?.toInt() ?: -1
    val fgRgb = (map["fgRgb"] as? Number)?.toInt()
    val bgIdx = (map["bg"] as? Number)?.toInt() ?: -1
    val bgRgb = (map["bgRgb"] as? Number)?.toInt()
    val style = VtStyle(
        foreground = VtColor(fgIdx, fgRgb),
        background = VtColor(bgIdx, bgRgb),
        bold = map["bold"] == true,
        faint = map["faint"] == true,
        italic = map["italic"] == true,
        underline = map["underline"] == true,
        blink = map["blink"] == true,
        inverse = map["inverse"] == true,
        concealed = map["concealed"] == true,
        crossedOut = map["crossedOut"] == true,
    )
    return VtCell(text = text, style = style, continuation = cont)
}
