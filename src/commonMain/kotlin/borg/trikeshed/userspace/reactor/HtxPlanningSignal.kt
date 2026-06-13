package borg.trikeshed.userspace.reactor

import borg.trikeshed.userspace.FanoutEvent

const val HTX_PLANNING_EVENT_TYPE: Int = 0x485458

/**
 * Stable, planning-oriented vocabulary between HTX/CCEK fanout and Hermes Kanban.
 *
 * HTX publishes planning-worthy facts; Hermes owns task lifecycle and decomposition.
 */
sealed interface HtxPlanningSignal {
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
    ) : HtxPlanningSignal

    data class ProgressNote(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val taskId: String,
    ) : HtxPlanningSignal

    data class NeedsHuman(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val taskId: String,
        val reason: String,
    ) : HtxPlanningSignal

    data class Resolved(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val taskId: String,
        val summary: String,
    ) : HtxPlanningSignal
}

/**
 * Typed HTX-side planning facts published through the reactor fanout.
 *
 * These are deliberately coarse-grained. They are what the HTX side knows after
 * protocol normalization, before Hermes turns them into board mutations.
 */
sealed interface HtxPlanningEvent : FanoutEvent {
    val connectionId: String
    val streamId: String
    val sequence: Long
    val title: String
    val detail: String
    val metadata: Map<String, String>
    val dedupeKey: String?

    override val eventType: Int
        get() = HTX_PLANNING_EVENT_TYPE

    data class IntentDetected(
        override val connectionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val title: String,
        override val detail: String,
        override val metadata: Map<String, String> = emptyMap(),
        override val dedupeKey: String? = null,
        val workspace: String,
    ) : HtxPlanningEvent

    data class ProgressObserved(
        override val connectionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val title: String,
        override val detail: String,
        override val metadata: Map<String, String> = emptyMap(),
        override val dedupeKey: String? = null,
        val taskId: String,
    ) : HtxPlanningEvent

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
    ) : HtxPlanningEvent

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
    ) : HtxPlanningEvent
}
