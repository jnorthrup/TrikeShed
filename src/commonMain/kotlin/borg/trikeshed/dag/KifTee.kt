package borg.trikeshed.dag

import borg.trikeshed.isam.synchronizedLock
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.contains
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.filter
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.view

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
 * into the network. Each change uses one [KifKnowledgeBase.replace] under
 * the bank's lock, so readers cannot see a half-replaced projection. The
 * bank retracts by its exact KIF string key; the Rete fact remains the source
 * of record.
 */
class KifTee(val bank: KifKnowledgeBase) {
    private val gate = Any()
    private val told = HashMap<Pair<String, String>, Series<KifExpr>>()

    /** Facts whose projection this tee currently holds in the bank. */
    fun trackedCount(): Int = synchronizedLock(gate) { told.size }

    /** Last applied projection for a fact; never recomputed from a reader's snapshot. */
    fun projection(id: FactId): Series<KifExpr>? = synchronizedLock(gate) { told[id.pair] }

    /** Register on [net]; the disposer detaches (the bank keeps what was told). */
    fun attach(net: ReteNetwork): AutoCloseable = net.observe { op, fact -> apply(op, fact) }

    /**
     * Tell every fact already in [net] — for a tee attached after facts exist
     * (the late-module case). Reads under the network lock; a fact seen both
     * here and through the observer is projected once.
     */
    suspend fun prime(net: ReteNetwork) {
        net.snapshot { facts -> for (fact in facts.view) apply(ReteOp.ASSERT, fact) }
    }

    /** The projection step itself, usable without a network (tests, replays). */
    fun apply(op: ReteOp, fact: ReteStoredFact) {
        when (op) {
            ReteOp.ASSERT, ReteOp.MODIFY -> project(fact)
            ReteOp.RETRACT -> unproject(fact.factId)
        }
    }

    private fun project(fact: ReteStoredFact) = synchronizedLock(gate) {
        val next = PlaneFacts.toKif(fact)
        val id = fact.factId.pair
        val previous = told[id]
        if (previous?.toList() == next.toList()) return@synchronizedLock
        val gone = previous?.filter { it !in next } ?: emptySeriesOf()
        bank.replace(gone.view, next.view)
        told[id] = next
    }

    private fun unproject(id: FactId) = synchronizedLock(gate) {
        val key = id.pair
        val previous = told[key] ?: return@synchronizedLock
        bank.replace(previous.view, emptySeriesOf<KifExpr>().view)
        told.remove(key)
        Unit
    }

    companion object {
        /** One call for the daemon wiring: `KifTee.attach(rete, kifBank)`. */
        fun attach(net: ReteNetwork, bank: KifKnowledgeBase): Join<KifTee, AutoCloseable> {
            val tee = KifTee(bank)
            return tee j tee.attach(net)
        }
    }
}
