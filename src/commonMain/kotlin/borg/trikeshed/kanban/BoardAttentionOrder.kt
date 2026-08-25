package borg.trikeshed.kanban

import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.RelationKind

/**
 * BoardAttentionOrder — belief-driven backlog garnish. TRIAGE/TODO cards get
 * an attention score from one resonance sweep over the bag's vector plane
 * (support front minus refutation front); a card whose refutation front is a
 * substantial fraction of its support front is CONTESTED. Board-cohort drift
 * is the Hotelling T² of the "kanban"-taxonomy beliefs against the whole bag.
 *
 * Strictly garnish: with the bag OFF the board JSON is byte-identical minus
 * the attention/contested fields (the Phase-5 gate).
 */
object BoardAttentionOrder {

    data class Garnish(val score: Float, val contested: Boolean)

    fun angularOf(row: CardRow): Long = AngularCodec.encode(
        relation = RelationKind.CAUSALITY,
        taxonomyKey = "kanban",
        subjectTerm = termOf(row),
        objectTerm = row.col.wire,
    )

    /** Context term ladder shared with the review bridge: tag → meaningful title word → jobId. */
    fun termOf(row: CardRow): String =
        row.tags.firstOrNull()
            ?: row.title.split(' ').firstOrNull { it.length > 3 }?.lowercase()
            ?: row.jobId

    /** The bag's own activation law (s⁴ peak contrast × pri) re-applied per centroid:
     *  front membership is global top-k, so relevance must be re-weighted by distance here. */
    private fun act(centroid: Long, angular: Long, pri: Float): Float {
        val s = 1f - (angular xor centroid).countOneBits() * 0.015625f
        val s2 = s * s
        return s2 * s2 * (pri + 0.01f)
    }

    /** One resonance sweep per backlog card. Zero model calls; autovec plane only. */
    fun garnish(bag: BeliefBagElement, rows: Collection<CardRow>, k: Int = 4): Map<String, Garnish> {
        val out = HashMap<String, Garnish>()
        for (row in rows) {
            if (row.col != BoardCol.TRIAGE && row.col != BoardCol.TODO) continue
            val centroid = angularOf(row)
            val res = bag.resonate(centroid, k)
            val support = res.synonyms.maxOfOrNull { act(centroid, it.angular, it.pri) } ?: 0f
            val refute = res.antonyms.maxOfOrNull { act(centroid, it.angular, it.pri) } ?: 0f
            // Contested = the refutation front reaches INTO this card's neighborhood
            // (pri asymmetry is systemic — negative evidence decays priority — so
            // proximity, not magnitude, is the honest signal).
            val contestedNear = res.antonyms.any { (it.angular xor centroid).countOneBits() <= 4 }
            out[row.jobId] = Garnish(
                score = support - refute,
                contested = support > 0f && contestedNear,
            )
        }
        return out
    }

    /** Board-cohort drift: T² of kanban-taxonomy beliefs vs the bag's whole field. */
    suspend fun driftT2(bag: BeliefBagElement): Float {
        val sig = AngularCodec.taxonomySigOfKey("kanban")
        return bag.field().hotelling { a ->
            AngularCodec.Fields.extract(a, AngularCodec.Fields.TAXONOMY_MASK, AngularCodec.Fields.TAXONOMY_SHIFT) == sig
        }
    }
}
