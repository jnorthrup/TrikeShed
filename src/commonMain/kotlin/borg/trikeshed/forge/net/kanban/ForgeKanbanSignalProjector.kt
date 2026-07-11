package borg.trikeshed.forge.net.kanban

import borg.trikeshed.userspace.FanoutEvent

/**
 * Projects Forge Kanban fanout facts into sink-facing signals.
 */
class ForgeKanbanSignalProjector {

    fun project(event: FanoutEvent): ForgeKanbanSignal? = when (event) {
        is ForgeKanbanEvent.IntentDetected -> ForgeKanbanSignal.NewIntent(
            idempotencyKey = event.dedupeKey ?: idempotencyKeyFor(event.connectionId, event.streamId, event.sequence),
            title = event.title,
            body = renderBody(event.detail, metadataFor(event)),
            metadata = metadataFor(event),
            workspace = event.workspace,
        )

        is ForgeKanbanEvent.ProgressObserved -> ForgeKanbanSignal.ProgressNote(
            idempotencyKey = event.dedupeKey ?: idempotencyKeyFor(event.connectionId, event.streamId, event.sequence),
            title = event.title,
            body = renderBody(event.detail, metadataFor(event)),
            metadata = metadataFor(event),
            taskId = event.taskId,
        )

        is ForgeKanbanEvent.HumanInterventionRequired -> ForgeKanbanSignal.NeedsHuman(
            idempotencyKey = event.dedupeKey ?: idempotencyKeyFor(event.connectionId, event.streamId, event.sequence),
            title = event.title,
            body = renderBody(event.detail, metadataFor(event)),
            metadata = metadataFor(event),
            taskId = event.taskId,
            reason = event.reason,
        )

        is ForgeKanbanEvent.ResolutionObserved -> ForgeKanbanSignal.Resolved(
            idempotencyKey = event.dedupeKey ?: idempotencyKeyFor(event.connectionId, event.streamId, event.sequence),
            title = event.title,
            body = renderBody(event.detail, metadataFor(event)),
            metadata = metadataFor(event),
            taskId = event.taskId,
            summary = event.summary,
        )

        else -> null
    }

    private fun metadataFor(event: ForgeKanbanEvent): Map<String, String> = buildMap {
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
            "forge:$connectionId:$streamId:$sequence"
    }
}