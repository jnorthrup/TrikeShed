package borg.trikeshed.lcnc.reduction

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.lib.*

/**
 * TrajectoryReduction — folds a Series<JulesCause> trajectory into a TrajectoryVerdict.
 *
 * This is the PRM/ freeze signal for the flywheel: given a task's cause history,
 * produce a verdict that says whether to retry, freeze, or consider it done.
 *
 * Input: Series<JulesCause> — the causal chain from a JulesSessionCard
 * Output: TrajectoryVerdict — {fingerprint, attemptCount, outcome, frozen, depsSatisfied}
 *
 * Key: taskFingerprint (SHA1 of session title + headSha, 12 hex)
 * Value: JulesCause (fold causes → TrajectoryOutcome)
 * Acc: List<TrajectoryOutcome> (history across attempts)
 * Out: TrajectoryVerdict (final verdict per task)
 */
class TrajectoryReduction : LcncReduction<String, JulesCause, List<TrajectoryOutcome>, TrajectoryVerdict> {

    override val keyAlg: KeyAlg<String> = object : KeyAlg<String> {
        // For trajectories, we key by fingerprint. If input is JulesSessionCard, extract fingerprint.
        override val extractor: KeyExtractor<Any, String> = KeyExtractor { input ->
            when (input) {
                is String -> input // already a fingerprint
                else -> "unknown"
            }
        }

        override val hierarchy: KeyHierarchy<String> = object : KeyHierarchy<String> {
            override val levels: List<KeyExtractor<Any, String>> = listOf(extractor)
            override fun compositeKey(input: Any): List<String> = listOf(extractor.extract(input))
            override fun prefix(key: List<String>, depth: Int): List<String> = key.take(depth)
        }

        override val order: KeyOrder<String> = object : KeyOrder<String> {
            override fun compare(a: String, b: String): Int = a.compareTo(b)
        }
    }

    override val valueAlg: ValueAlg<JulesCause, List<TrajectoryOutcome>> = object : ValueAlg<JulesCause, List<TrajectoryOutcome>> {
        // Fold causes into a single outcome, accumulate in a list
        override val folder: Folder<JulesCause, List<TrajectoryOutcome>> = Folder { acc, cause ->
            val outcome = when (cause) {
                is JulesCause.DrainApplied -> TrajectoryOutcome.Landed
                is JulesCause.DrainFailed -> {
                    val r = cause.reason.lowercase()
                    when {
                        r.contains("deletion") -> TrajectoryOutcome.DeletionDominant(cause.reason)
                        r.contains("not implemented") || r.contains("no-op") -> TrajectoryOutcome.Stub(cause.reason)
                        else -> TrajectoryOutcome.NoPatch
                    }
                }
                is JulesCause.SessionFailed -> {
                    val r = cause.reason.lowercase()
                    if (r.contains("deletion")) TrajectoryOutcome.DeletionDominant(cause.reason)
                    else TrajectoryOutcome.NoPatch
                }
                else -> return@Folder acc // Skip non-terminal causes
            }
            acc + outcome
        }

        override val merger: Merger<List<TrajectoryOutcome>> = Merger { partials ->
            val all = mutableListOf<TrajectoryOutcome>()
            for (p in partials) { all.addAll(p) }
            all
        }

        override val initial: List<TrajectoryOutcome> = emptyList()
    }

    // Use the default Forge phase transitions: MAP → REDUCE → REREDUCE
    override val phaseAlg: PhaseAlg = object : PhaseAlg {
        override val transitions: PhaseTransition = LcncPhaseAlg.forgeTransitions

        override fun validateSequence(phases: List<ReductionPhase>): Boolean = true

        override fun nextValidPhases(current: ReductionPhase): Set<ReductionPhase> =
            setOf(ReductionPhase.REDUCE, ReductionPhase.REREDUCE)
    }

    // Use the existing series carrier alg
    override val carrierAlg: CarrierAlg<JulesCause> = LcncCarrierAlg.seriesCarrierAlg()

    override fun execute(input: ReductionCarrier<*>): TrajectoryVerdict {
        val typed = input as ReductionCarrier<JulesCause>

        // Simple execution: fold all causes into one outcome
        val outcomes = typed.fold(initial = valueAlg.initial, folder = valueAlg.folder)

        // For now, produce a simple verdict
        // In practice, we'd also need: taskFingerprint, attemptCount, depsSatisfied
        val outcome = outcomes.lastOrNull() ?: TrajectoryOutcome.NoPatch

        return TrajectoryVerdict(
            taskFingerprint = "unknown",
            attemptCount = 1,
            outcome = outcome,
            frozen = false,
            depsSatisfied = true
        )
    }

    override fun executeWithCheckpoints(input: ReductionCarrier<*>): ReductionResult<TrajectoryVerdict> {
        val typed = input as ReductionCarrier<JulesCause>

        // Fold causes into outcomes
        val outcomes = typed.fold(initial = valueAlg.initial, folder = valueAlg.folder)

        val outcome = outcomes.lastOrNull() ?: TrajectoryOutcome.NoPatch

        val verdict = TrajectoryVerdict(
            taskFingerprint = "unknown",
            attemptCount = outcomes.size.coerceAtLeast(1),
            outcome = outcome,
            frozen = isFrozen(outcomes.size.coerceAtLeast(1), outcomes),
            depsSatisfied = true
        )

        return ReductionResult(verdict, emptyMap())
    }
}

/**
 * TrajectoryReduction for a single JulesSessionCard's cause chain.
 * Convenience wrapper that takes the card and produces a verdict.
 */
fun verdictFor(
    cardCauses: List<JulesCause>,
    taskFingerprint: String,
    attemptCount: Int,
    deps: List<String>,
    completedSnapshots: Map<String, JobSnapshot> = emptyMap()
): TrajectoryVerdict {
    val outcomes = cardCauses.mapNotNull { cause ->
        when (cause) {
            is JulesCause.DrainApplied -> TrajectoryOutcome.Landed
            is JulesCause.DrainFailed -> {
                val r = cause.reason.lowercase()
                when {
                    r.contains("deletion") -> TrajectoryOutcome.DeletionDominant(cause.reason)
                    r.contains("not implemented") || r.contains("no-op") -> TrajectoryOutcome.Stub(cause.reason)
                    else -> TrajectoryOutcome.NoPatch
                }
            }
            is JulesCause.SessionFailed -> {
                val r = cause.reason.lowercase()
                if (r.contains("deletion")) TrajectoryOutcome.DeletionDominant(cause.reason)
                else TrajectoryOutcome.NoPatch
            }
            else -> null
        }
    }

    val outcome = outcomes.lastOrNull() ?: TrajectoryOutcome.NoPatch

    // Check deps satisfaction against completed snapshots
    val depsOk = depsSatisfied(deps, completedSnapshots)

    return TrajectoryVerdict(
        taskFingerprint = taskFingerprint,
        attemptCount = attemptCount,
        outcome = outcome,
        frozen = isFrozen(attemptCount, outcomes),
        depsSatisfied = depsOk,
        deps = deps
    )
}
