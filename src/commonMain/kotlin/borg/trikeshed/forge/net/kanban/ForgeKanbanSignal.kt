package borg.trikeshed.forge.net.kanban

sealed interface ForgeKanbanSignal {
    val idempotencyKey: String
    val title: String
    val body: String
    val metadata: Map<String, String>

    data class NewIntent(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val workspace: String,
    ) : ForgeKanbanSignal

    data class ProgressNote(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val taskId: String,
    ) : ForgeKanbanSignal

    data class NeedsHuman(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val taskId: String,
        val reason: String,
    ) : ForgeKanbanSignal

    data class Resolved(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val taskId: String,
        val summary: String,
    ) : ForgeKanbanSignal
}

sealed interface ForgeKanbanEvent : borg.trikeshed.userspace.FanoutEvent {
    val connectionId: String
    val streamId: String
    val sequence: Long
    val title: String
    val detail: String
    val metadata: Map<String, String>
    val dedupeKey: String?

    override val eventType: Int
        get() = 0x464F5247 // "FORG" in hex

    data class IntentDetected(
        override val connectionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val title: String,
        override val detail: String,
        override val metadata: Map<String, String> = emptyMap(),
        override val dedupeKey: String? = null,
        val workspace: String,
    ) : ForgeKanbanEvent

    data class ProgressObserved(
        override val connectionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val title: String,
        override val detail: String,
        override val metadata: Map<String, String> = emptyMap(),
        override val dedupeKey: String? = null,
        val taskId: String,
    ) : ForgeKanbanEvent

    data class HumanInterventionRequired(
        override val connectionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val title: String,
        override val detail: String,
        override val metadata: Map<String, String> = emptyMap(),
        override val dedupeKey: String? = null,
        val taskId: String,
        val reason: String,
    ) : ForgeKanbanEvent

    data class ResolutionObserved(
        override val connectionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val title: String,
        override val detail: String,
        override val metadata: Map<String, String> = emptyMap(),
        override val dedupeKey: String? = null,
        val taskId: String,
        val summary: String,
    ) : ForgeKanbanEvent
}