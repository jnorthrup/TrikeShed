package borg.trikeshed.userspace.reactor

import borg.trikeshed.userspace.FanoutEvent

/**
 * Applies projected HTX planning signals to Hermes Kanban through the supported CLI.
 */
class HtxKanbanConduit(
    private val projector: HtxPlanningSignalProjector = HtxPlanningSignalProjector(),
    private val hermes: HermesKanbanCli,
) {
    suspend fun onEvent(event: FanoutEvent): Boolean {
        val signal = projector.project(event) ?: return false
        return accept(signal)
    }

    suspend fun accept(signal: HtxPlanningSignal): Boolean {
        when (signal) {
            is HtxPlanningSignal.NewIntent -> {
                hermes.createTriageCard(
                    title = signal.title,
                    body = signal.body,
                    idempotencyKey = signal.idempotencyKey,
                    workspace = signal.workspace,
                )
            }

            is HtxPlanningSignal.ProgressNote -> {
                hermes.comment(signal.taskId, signal.body)
            }

            is HtxPlanningSignal.NeedsHuman -> {
                hermes.block(signal.taskId, signal.reason)
            }

            is HtxPlanningSignal.Resolved -> {
                hermes.complete(signal.taskId, signal.summary, signal.metadata)
            }
        }
        return true
    }
}
