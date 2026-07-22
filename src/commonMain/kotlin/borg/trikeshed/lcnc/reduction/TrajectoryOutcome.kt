package borg.trikeshed.lcnc.reduction

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.job.JobSnapshot

/**
 * Constants for trajectory-based dispatch decisions.
 */
object TrajectoryConfig {
    /** Number of failed attempts before a task is frozen (stops auto-retry). */
    const val FROZEN_AFTER = 3

    /** Number of attempts before we stop auto-requeueing entirely. */
    const val MAX_ATTEMPTS = 5

    /** Task fingerprint = SHA1(title + headSha), truncated to this many hex chars. */
    const val FINGERPRINT_LENGTH = 12
}

/**
 * Outcome categories derived from a JulesCause trajectory.
 * Maps raw causes to categorical verdicts for PRM/ freeze decisions.
 */
sealed class TrajectoryOutcome {
    /** Jules reached terminal state without emitting a patch. */
    data object NoPatch : TrajectoryOutcome()

    /** Patch was deletion-dominant (more deleted than added). */
    data class DeletionDominant(val path: String) : TrajectoryOutcome()

    /** Patch contains stubs, NotImplementedError, or no-op implementations. */
    data class Stub(val reason: String) : TrajectoryOutcome()

    /** Tests failed (gate red). */
    data class GateRed(val failures: List<String>) : TrajectoryOutcome()

    /** Patch was successfully landed. */
    data object Landed : TrajectoryOutcome()
}

/**
 * Final verdict from the TrajectoryReduction.
 *
 * @param taskFingerprint SHA1 hash of session title + headSha (12 hex chars)
 * @param attemptCount Number of dispatch attempts for this task
 * @param outcome The final trajectory outcome category
 * @param frozen True if attemptCount >= 3 AND last 3 outcomes are the same category
 * @param depsSatisfied True if all dependencies have completed snapshots
 */
data class TrajectoryVerdict(
    val taskFingerprint: String,
    val attemptCount: Int,
    val outcome: TrajectoryOutcome,
    val frozen: Boolean,
    val depsSatisfied: Boolean,
    val deps: List<String> = emptyList()
)

/**
 * Fold a JulesCause trajectory into a TrajectoryOutcome.
 * Inspects the terminal causes in sequence order.
 */
fun foldTrajectory(
    causes: List<JulesCause>,
    attemptCount: Int
): TrajectoryOutcome {
    if (causes.isEmpty()) return TrajectoryOutcome.NoPatch

    // Scan for terminal outcomes, preferring the last one
    var lastOutcome: TrajectoryOutcome = TrajectoryOutcome.NoPatch

    for (cause in causes.reversed()) {
        lastOutcome = when (cause) {
            is JulesCause.DrainApplied -> return TrajectoryOutcome.Landed

            is JulesCause.DrainFailed -> {
                val reason = cause.reason.lowercase()
                when {
                    reason.contains("deletion") -> TrajectoryOutcome.DeletionDominant(cause.reason)
                    reason.contains("not implemented") || reason.contains("no-op") ->
                        TrajectoryOutcome.Stub(cause.reason)
                    else -> TrajectoryOutcome.NoPatch
                }
            }

            is JulesCause.SessionFailed -> {
                val reason = cause.reason.lowercase()
                when {
                    reason.contains("deletion") -> TrajectoryOutcome.DeletionDominant(cause.reason)
                    else -> TrajectoryOutcome.NoPatch
                }
            }

            is JulesCause.PatchArrived -> {
                // Patch arrived but not yet drained — check if there was a subsequent failure
                continue
            }

            else -> continue
        }
        // Found a terminal outcome
        if (lastOutcome !is TrajectoryOutcome.NoPatch || cause is JulesCause.DrainFailed) {
            return lastOutcome
        }
    }

    // No terminal outcome found
    return TrajectoryOutcome.NoPatch
}

/**
 * Determine if a task should be frozen based on attempt count and outcome history.
 * Frozen = FROZEN_AFTER (3) attempts AND last 3 outcomes are the same category.
 */
fun isFrozen(attemptCount: Int, outcomeHistory: List<TrajectoryOutcome>): Boolean {
    if (attemptCount < TrajectoryConfig.FROZEN_AFTER) return false
    if (outcomeHistory.size < TrajectoryConfig.FROZEN_AFTER) return false

    val recent = outcomeHistory.takeLast(TrajectoryConfig.FROZEN_AFTER)
    val firstCategory = recent.first()::class
    return recent.all { it::class == firstCategory }
}

/**
 * Check if all dependencies for a task have completed snapshots.
 *
 * @param deps List of dependency job IDs
 * @param completedSnapshots Map of jobId → JobSnapshot for completed jobs
 * @return true if all deps have completed snapshots, false otherwise
 */
fun depsSatisfied(
    deps: List<String>,
    completedSnapshots: Map<String, JobSnapshot>
): Boolean {
    if (deps.isEmpty()) return true
    return deps.all { depId ->
        completedSnapshots.containsKey(depId)
    }
}

/**
 * Check if a task should be permanently abandoned (exceeded MAX_ATTEMPTS).
 */
fun isAbandoned(attemptCount: Int): Boolean =
    attemptCount >= TrajectoryConfig.MAX_ATTEMPTS
