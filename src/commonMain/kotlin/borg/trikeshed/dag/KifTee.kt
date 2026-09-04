package borg.trikeshed.dag

import borg.trikeshed.isam.synchronizedLock
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase

/**
 * The KIF half of the join: every plane fact the [ReteNetwork] applies is
 * projected by [PlaneFacts.toKif] into ONE [KifKnowledgeBase] — the daemon's
 * `kifBank` — and un-projected when the fact goes away, so the bank stays a
 * function of working memory instead of a log of everything it ever held.
 *
 *  - ASSERT  → `bank.assert` each tuple of `toKif(fact)`;
 *  - MODIFY  → retract the tuples this tee told for that [FactId] before
 *              (the observer only sees the NEW fact, so the old projection is
 *              remembered here), then assert the new ones;
 *  - RETRACT → retract the remembered projection (the observer hands the fact
 *              as it was, so `toKif` of it is the same set).
 *
 * Idempotent: the network never reports an identical re-assert, and a fact
 * whose projection equals the remembered one is skipped here too, so telling
 * the same fact twice leaves the bank unchanged. Tuples carry the fact IRI
 * (`fact:<partition>/<localId>`), so no two facts share a tuple and one fact's
 * retraction cannot remove another's.
 *
 * The observer runs under the network's write lock and only touches the bank
 * (its own lock, no rete write), so it is safe there — it never calls back
 * into the network. A reader querying the bank between the retract and the
 * re-assert of a MODIFY can see the fact's tuples momentarily absent; the
 * bank is the light solver, not the source of record, and the Rete fact is.

 *
 * Delta (2026-09-04): each fact's change is ONE [KifKnowledgeBase.replace]
 * (retract the tuples that left, assert the new set) under one take of the
 * bank's lock, so the momentary absence above no longer happens, and the bank
 * retracts by key, so the time spent inside the network's write lock is per
 * tuple, not per tuple times bank size (the graal tick was holding the lock
 * for seconds at ~200k tuples).
 */
class KifTee(val bank: KifKnowledgeBase) {
    private val gate = Any()
    private val told = HashMap<FactId, List<KifExpr>>()

    /** Facts whose projection this tee currently holds in the bank. */
    fun trackedCount(): Int = synchronizedLock(gate) { told.size }

    /** Register on [net]; the disposer detaches (the bank keeps what was told). */
    fun attach(net: ReteNetwork): AutoCloseable = net.observe { op, fact -> apply(op, fact) }

    /**
     * Tell every fact already in [net] — for a tee attached after facts exist
     * (the late-module case). Reads under the network lock; a fact seen both
     * here and through the observer is projected once.
     */
    suspend fun prime(net: ReteNetwork) {
        for (fact in net.snapshot()) apply(ReteOp.ASSERT, fact)
    }

    /** The projection step itself, usable without a network (tests, replays). */
    fun apply(op: ReteOp, fact: ReteStoredFact) {
        when (op) {
            ReteOp.ASSERT, ReteOp.MODIFY -> project(fact)
            ReteOp.RETRACT -> unproject(fact.factId)
        }
    }

    private fun project(fact: ReteStoredFact) {
        val next = PlaneFacts.toKif(fact)
        val previous = synchronizedLock(gate) { told[fact.factId] }
        if (previous == next) return
        val gone = if (previous == null) emptyList() else previous.filter { it !in next }
        bank.replace(gone, next)
        synchronizedLock(gate) { told[fact.factId] = next }
    }

    private fun unproject(id: FactId) {
        val previous = synchronizedLock(gate) { told.remove(id) } ?: return
        bank.replace(previous, emptyList())
    }

    companion object {
        /** One call for the daemon wiring: `KifTee.attach(rete, kifBank)`. */
        fun attach(net: ReteNetwork, bank: KifKnowledgeBase): Pair<KifTee, AutoCloseable> {
            val tee = KifTee(bank)
            return tee to tee.attach(net)
        }
    }
}
