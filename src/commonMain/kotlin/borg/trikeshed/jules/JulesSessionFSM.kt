package borg.trikeshed.jules
sealed class JulesSessionState {
    data object Queued : JulesSessionState()
    data object Planning : JulesSessionState()
    data object AwaitingPlanApproval : JulesSessionState()
    data object InProgress : JulesSessionState()
    data object AwaitingUserFeedback : JulesSessionState()
    data object Completed : JulesSessionState()
    data object Failed : JulesSessionState()
    data object Cancelled : JulesSessionState()
}
fun String.toJulesState(): JulesSessionState = when(this) {
    "QUEUED" -> JulesSessionState.Queued
    "PLANNING" -> JulesSessionState.Planning
    "AWAITING_PLAN_APPROVAL" -> JulesSessionState.AwaitingPlanApproval
    "IN_PROGRESS" -> JulesSessionState.InProgress
    "AWAITING_USER_FEEDBACK" -> JulesSessionState.AwaitingUserFeedback
    "COMPLETED" -> JulesSessionState.Completed
    "FAILED" -> JulesSessionState.Failed
    "CANCELLED" -> JulesSessionState.Cancelled
    else -> JulesSessionState.Queued
}