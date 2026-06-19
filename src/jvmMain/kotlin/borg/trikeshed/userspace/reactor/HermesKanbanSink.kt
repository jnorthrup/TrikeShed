package borg.trikeshed.userspace.reactor

/**
 * JVM sink adapter that keeps Hermes CLI at the edge while the conduit and
 * projection remain in commonMain.
 */
class HermesKanbanSink(
    private val hermes: HermesKanbanCli,
) : HtxPlanningSignalSink {
    override suspend fun accept(signal: HtxPlanningSignal): Boolean {
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
