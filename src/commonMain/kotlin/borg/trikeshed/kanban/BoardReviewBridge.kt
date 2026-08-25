package borg.trikeshed.kanban

import borg.trikeshed.dag.Activation
import borg.trikeshed.job.JobCommand
import borg.trikeshed.kanban.rules.BoardRules
import borg.trikeshed.narsese.TurnReviewElement

/**
 * BoardReviewBridge — board experience → NARS induction (garnish: the board is
 * fully functional with this absent). Windowed committed batches become
 * TurnFacts for [TurnReviewElement.reviewTurn] (per-fact OBSERVATION,
 * pairwise INDUCTION for co-transitions, intake-capped):
 *
 *  - Complete → positive evidence; Fail/Cancel/Retract → negative;
 *  - rule firings mint (ruleId → outcome) OBSERVATIONs;
 *  - a HUMAN counter-move within [counterMoveWindowMs] of a rule's move mints
 *    NEGATIVE evidence on that rule — the attention economy learns which
 *    rules actually help.
 *
 * Chain depth stays ≤2 (observation + the review's own pairwise induction);
 * no abduction on the board path; zero model calls.
 */
class BoardReviewBridge(
    private val review: TurnReviewElement,
    private val cardLookup: (String) -> CardRow?,
    private val counterMoveWindowMs: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = { 0L },
) {
    private val pending = ArrayList<TurnReviewElement.TurnFact>()

    private class RuleMove(val ruleId: String, val col: String, val atMs: Long)

    private val ruleMoves = HashMap<String, RuleMove>()

    val pendingCount: Int get() = pending.size

    fun onCommitted(ev: BoardCommitted) {
        if (ev.command is JobCommand.Move) {
            val ruleTag = "#${BoardRules.DEPENDENCY_READY}#"
            if (ruleTag in ev.command.idempotencyKey) {
                ruleMoves[ev.jobId] = RuleMove(BoardRules.DEPENDENCY_READY, ev.col.wire, clock())
            } else {
                val rm = ruleMoves.remove(ev.jobId)
                if (rm != null && clock() - rm.atMs <= counterMoveWindowMs && ev.col.wire != rm.col) {
                    // The human took it back: the rule's proposal earns negative evidence.
                    pending.add(TurnReviewElement.TurnFact(rm.ruleId, ok = false, contextTerm = contextOf(ev), objectTerm = rm.col))
                }
            }
        }
        val ok = when (ev.command) {
            is JobCommand.Fail, is JobCommand.Cancel, is JobCommand.Retract -> false
            else -> true
        }
        pending.add(
            TurnReviewElement.TurnFact(
                verb = ev.command.operationName,
                ok = ok,
                contextTerm = contextOf(ev),
                objectTerm = ev.col.wire,
            ),
        )
    }

    /** A board rule fired (post-refraction): the rule itself becomes evidence. */
    fun onRuleFired(a: Activation) {
        pending.add(
            TurnReviewElement.TurnFact(
                verb = a.ruleId,
                ok = true,
                contextTerm = a.bindings["jobId"] ?: a.bindings["column"] ?: "board",
                objectTerm = a.ruleId,
            ),
        )
    }

    /** Context term ladder: first tag → first meaningful title word → jobId. */
    private fun contextOf(ev: BoardCommitted): String {
        val row = cardLookup(ev.jobId)
        row?.tags?.firstOrNull()?.let { return it }
        row?.title?.split(' ')?.firstOrNull { it.length > 3 }?.let { return it.lowercase() }
        return ev.jobId
    }

    /** Drain the window through the pure induction pass. Bounded by the review's intake cap. */
    suspend fun flush(turnSucceeded: Boolean = true): List<Pair<Long, String>> {
        if (pending.isEmpty()) return emptyList()
        val facts = pending.toList()
        pending.clear()
        return review.reviewTurn(facts, turnSucceeded)
    }
}
