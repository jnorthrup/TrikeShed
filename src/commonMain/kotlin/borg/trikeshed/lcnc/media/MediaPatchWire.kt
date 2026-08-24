package borg.trikeshed.lcnc.media

import borg.trikeshed.lib.view
import borg.trikeshed.terminal.VtCell
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
