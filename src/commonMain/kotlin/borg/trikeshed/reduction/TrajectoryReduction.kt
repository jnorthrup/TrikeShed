package borg.trikeshed.reduction

import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.ws.Sha1Common
import borg.trikeshed.lib.j
import borg.trikeshed.lib.α
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get

/**
 * Top-level convenience: fold a list of causes into a verdict without building a
 * full TrajectoryPayload. Used by the CLI and by callers that already have a
 * plain List<JulesCause>. Uses a synthetic empty-deps TrajectoryPayload.
 */
fun verdictFor(
    cardCauses: List<JulesCause>,
    taskFingerprint: String,
    attemptCount: Int,
    deps: List<String>,
): TrajectoryVerdict {
    val series = emptySeriesOf<JulesCause>().let { empty ->
        // Build a Series<JulesCause> from a list — uses the same α map mechanism.
        var result = empty
        for (c in cardCauses) result = result α { c }
        result
    }
    val payload = TrajectoryPayload(
        title = taskFingerprint,
        headSha = taskFingerprint,
        causes = series,
        depJobIds = deps,
    )
    val carrier = TrajectoryCarrier(payload)
    val reduction = TrajectoryReduction()
    return reduction.executeWithCheckpoints(carrier).output.copy(attemptCount = attemptCount)
}

data class TrajectoryPayload(
    val title: String,
    val headSha: String,
    val causes: Series<JulesCause>,
    val depJobIds: List<String> = emptyList(),
    val deps: Series<JobSnapshot> = emptySeriesOf()
)

class TrajectoryCarrier(val payload: TrajectoryPayload) : ReductionCarrier<JulesCause> by SeriesCarrier(payload.causes)

object TrajectoryPhaseAlg : PhaseAlg {
    override val transitions: PhaseTransition = LcncPhaseAlg.forgeTransitions
    override fun validateSequence(phases: List<ReductionPhase>): Boolean = true
    override fun nextValidPhases(current: ReductionPhase): Set<ReductionPhase> = emptySet()

    fun checkDeps(depJobIds: List<String>, deps: Series<JobSnapshot>): Boolean {
        if (depJobIds.isEmpty()) return true
        for (id in depJobIds) {
            var foundComplete = false
            for (i in 0 until deps.size) {
                val item = deps[i]
                if (item.jobId.value == id && item.lifecycle == "complete") {
                    foundComplete = true
                    break
                }
            }
            if (!foundComplete) return false
        }
        return true
    }
}

class TrajectoryReduction : LcncReduction<String, JulesCause, TrajectoryOutcome, TrajectoryVerdict> {
    override val keyAlg: KeyAlg<String> = object : KeyAlg<String> {
        override val extractor = KeyExtractor<Any, String> { input ->
            val payload = (input as TrajectoryCarrier).payload
            val text = payload.title + payload.headSha
            val hashBytes = Sha1Common.hash(text.encodeToByteArray())
            hashBytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }.take(12)
        }
        override val hierarchy = object : KeyHierarchy<String> {
            override val levels = emptyList<KeyExtractor<Any, String>>()
            override fun compositeKey(input: Any) = listOf(extractor.extract(input))
            override fun prefix(key: List<String>, depth: Int) = key
        }
        override val order = LcncKeyAlg.naturalKeyOrder<String>()
    }

    override val valueAlg: ValueAlg<JulesCause, TrajectoryOutcome> = object : ValueAlg<JulesCause, TrajectoryOutcome> {
        override val initial = TrajectoryOutcome.NoPatch
        override val folder = Folder<JulesCause, TrajectoryOutcome> { acc, cause ->
            parseCause(cause, acc)
        }
        override val merger = Merger<TrajectoryOutcome> { partials ->
            if (partials.size == 0) initial else partials[partials.size - 1]
        }
    }

    override val phaseAlg: PhaseAlg = TrajectoryPhaseAlg

    override val carrierAlg: CarrierAlg<JulesCause> = object : CarrierAlg<JulesCause> {
        override val carrier: (Any) -> ReductionCarrier<JulesCause> = { input ->
            if (input is TrajectoryPayload) TrajectoryCarrier(input)
            else SeriesCarrier(emptySeriesOf())
        }
    }

    private fun parseReason(reason: String): TrajectoryOutcome {
        return when {
            "no patch" in reason || "without a client" in reason -> TrajectoryOutcome.NoPatch
            "deletion-dominant" in reason -> {
                val path = reason.substringAfter("deletion-dominant").trim(':', ' ')
                TrajectoryOutcome.DeletionDominant(path)
            }
            "NotImplementedError" in reason || "no-op" in reason -> TrajectoryOutcome.Stub(reason)
            "red" in reason || "FAILED" in reason -> TrajectoryOutcome.GateRed(listOf(reason))
            else -> TrajectoryOutcome.GateRed(listOf(reason))
        }
    }

    private fun parseCause(cause: JulesCause, current: TrajectoryOutcome): TrajectoryOutcome = when (cause) {
        is JulesCause.DrainApplied -> TrajectoryOutcome.Landed
        is JulesCause.DrainFailed -> parseReason(cause.reason)
        is JulesCause.SessionFailed -> parseReason(cause.reason)
        is JulesCause.PatchArrived -> current
        else -> current
    }

    override fun execute(input: ReductionCarrier<*>): TrajectoryVerdict {
        return executeWithCheckpoints(input).output
    }

    override fun executeWithCheckpoints(input: ReductionCarrier<*>): ReductionResult<TrajectoryVerdict> {
        val carrier = input as TrajectoryCarrier
        val payload = carrier.payload

        val fingerprint = keyAlg.extractor.extract(carrier)
        val depsSatisfied = TrajectoryPhaseAlg.checkDeps(payload.depJobIds, payload.deps)

        val attemptCauses = ArrayList<JulesCause>()
        val mappedSeries = payload.causes α { cause ->
            if (cause is JulesCause.DrainFailed || cause is JulesCause.DrainApplied ||
                cause is JulesCause.SessionFailed || cause is JulesCause.PatchArrived) cause else null
        }
        for (i in 0 until mappedSeries.size) {
            val cause = mappedSeries[i]
            if (cause != null) attemptCauses.add(cause)
        }
        val attemptCount = attemptCauses.size

        val finalOutcome = attemptCauses.fold(valueAlg.initial as TrajectoryOutcome) { acc, cause ->
            parseCause(cause, acc)
        }

        var frozen = false
        if (attemptCount >= 3) {
            val last3 = attemptCauses.takeLast(3).map { parseCause(it, TrajectoryOutcome.NoPatch) }
            if (last3[0]::class == last3[1]::class && last3[1]::class == last3[2]::class) {
                frozen = true
            }
        }

        val verdict = TrajectoryVerdict(
            taskFingerprint = fingerprint,
            attemptCount = attemptCount,
            outcome = finalOutcome,
            frozen = frozen,
            depsSatisfied = depsSatisfied
        )

        return ReductionResult(verdict, emptyMap())
    }
}
