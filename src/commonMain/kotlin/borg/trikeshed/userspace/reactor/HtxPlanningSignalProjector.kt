package borg.trikeshed.userspace.reactor

import borg.trikeshed.userspace.FanoutEvent

/**
 * Projects HTX fanout facts into sink-facing planning signals.
 */
class HtxPlanningSignalProjector {

    fun project(event: FanoutEvent): HtxPlanningSignal? = when (event) {
        is HtxPlanningEvent.IntentDetected -> HtxPlanningSignal.NewIntent(
            idempotencyKey = event.dedupeKey ?: idempotencyKeyFor(event.connectionId, event.streamId, event.sequence),
            title = event.title,
            body = renderBody(event.detail, metadataFor(event)),
            metadata = metadataFor(event),
            workspace = event.workspace,
        )

        is HtxPlanningEvent.ProgressObserved -> HtxPlanningSignal.ProgressNote(
            idempotencyKey = event.dedupeKey ?: idempotencyKeyFor(event.connectionId, event.streamId, event.sequence),
            title = event.title,
            body = renderBody(event.detail, metadataFor(event)),
            metadata = metadataFor(event),
            taskId = event.taskId,
        )

        is HtxPlanningEvent.HumanInterventionRequired -> HtxPlanningSignal.NeedsHuman(
            idempotencyKey = event.dedupeKey ?: idempotencyKeyFor(event.connectionId, event.streamId, event.sequence),
            title = event.title,
            body = renderBody(event.detail, metadataFor(event)),
            metadata = metadataFor(event),
            taskId = event.taskId,
            reason = event.reason,
        )

        is HtxPlanningEvent.ResolutionObserved -> HtxPlanningSignal.Resolved(
            idempotencyKey = event.dedupeKey ?: idempotencyKeyFor(event.connectionId, event.streamId, event.sequence),
            title = event.title,
            body = renderBody(event.detail, metadataFor(event)),
            metadata = metadataFor(event),
            taskId = event.taskId,
            summary = event.summary,
        )

        else -> null
    }

    private fun metadataFor(event: HtxPlanningEvent): Map<String, String> = buildMap {
        putAll(event.metadata)
        put("connectionId", event.connectionId)
        put("streamId", event.streamId)
        put("sequence", event.sequence.toString())
        event.dedupeKey?.let { put("dedupeKey", it) }
    }

    private fun renderBody(detail: String, metadata: Map<String, String>): String = buildString {
        if (detail.isNotBlank()) {
            append(detail.trim())
        }
        if (metadata.isNotEmpty()) {
            if (isNotEmpty()) {
                append("\n\n")
            }
            append("Metadata:")
            metadata.keys.toList().sorted().forEach { key ->
                val value = metadata[key].orEmpty()
                append("\n- ")
                append(key)
                append('=')
                append(value)
            }
        }
    }

    companion object {
        fun idempotencyKeyFor(connectionId: String, streamId: String, sequence: Long): String =
            "htx:$connectionId:$streamId:$sequence"
    }
}
