package borg.trikeshed.reduction

sealed class TrajectoryOutcome {
    data object NoPatch : TrajectoryOutcome()
    data class DeletionDominant(val path: String) : TrajectoryOutcome()
    data class Stub(val reason: String) : TrajectoryOutcome()
    data class GateRed(val failures: List<String>) : TrajectoryOutcome()
    data object Landed : TrajectoryOutcome()
}

data class TrajectoryVerdict(
    val taskFingerprint: String,
    val attemptCount: Int,
    val outcome: TrajectoryOutcome,
    val frozen: Boolean,
    val depsSatisfied: Boolean
)
