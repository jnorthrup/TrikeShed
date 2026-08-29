package borg.trikeshed.kanban

import borg.trikeshed.dag.Activation
import borg.trikeshed.isam.synchronizedLock
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
    // Four concurrent callers reach this state (receipts collector, rete
    // productionSink, the 60s ticker's flush, the kanban.review runner) — every
    // pending/ruleMoves/failSinceFlush touch rides one gate. reviewTurn itself
    // runs OUTSIDE the gate (it suspends).
    private val gate = Any()
    private val pending = ArrayList<TurnReviewElement.TurnFact>()

    private class RuleMove(val ruleId: String, val col: String, val atMs: Long)

    private val ruleMoves = HashMap<String, RuleMove>()

    private var failSinceFlush = false

    val pendingCount: Int get() = synchronizedLock(gate) { pending.size }

    fun onCommitted(ev: BoardCommitted): Unit = synchronizedLock(gate) {
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
        if (ev.command is JobCommand.Fail) failSinceFlush = true
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
    fun onRuleFired(a: Activation): Unit = synchronizedLock(gate) {
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

    /** Drain the window through the pure induction pass. Bounded by the review's
     *  intake cap. [turnSucceeded] defaults to "no Fail commits since the last
     *  flush" so Nal.observe's failure dampening is reachable on the periodic
     *  flush path, not only when a caller passes false explicitly. */
    suspend fun flush(turnSucceeded: Boolean? = null): List<Pair<Long, String>> {
        // Drain atomically; two concurrent flushes must never review the same facts.
        val (facts, succeeded) = synchronizedLock(gate) {
            val s = turnSucceeded ?: !failSinceFlush
            failSinceFlush = false
            if (pending.isEmpty()) return@synchronizedLock null
            val f = pending.toList()
            pending.clear()
            f to s
        } ?: return emptyList()
        return review.reviewTurn(facts, succeeded)
    }
}

/**
 * BoardRuleAlertRing — the productions' live tail: the last [cap] activations
 * per ruleId, so kanban.alerts answers from memory instead of replaying the
 * kanban/rule blackboard receipts. Bag-independent (rules fire with NARS
 * off). No internal locking — the holder serializes writer (productionSink)
 * against readers.
 */
class BoardRuleAlertRing(private val cap: Int = 8) {
    private val rings = HashMap<String, ArrayDeque<Activation>>()

    fun retain(a: Activation) {
        val ring = rings.getOrPut(a.ruleId) { ArrayDeque(cap) }
        ring.addLast(a)
        while (ring.size > cap) ring.removeFirst()
    }

    fun tail(ruleId: String): List<Activation> = rings[ruleId]?.toList() ?: emptyList()
}
