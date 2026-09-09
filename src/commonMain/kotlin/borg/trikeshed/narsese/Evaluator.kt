package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId

/**
 * Who derived a belief.
 *
 * [DerivationReceipt.evaluatorCid] has always been a first-class field, and it is folded into the
 * receipt's canonical CID — so the identity of the deriver is content-addressed, and two
 * derivations that agree on premises and evidence but disagree on who made them are already
 * different citizens. That was the design. What was missing is that every evaluator in the tree
 * was the name of a code path: "turn-review", "causality-rete", "selectSkills". A pass guided by
 * a model recorded the guidance and not the guide, so "which brain induced this" had no answer
 * and no two models could be told apart after the fact.
 *
 * An [Evaluator] is that identity, and it says which kind it is:
 *
 *  - [pure] — an algorithm reached this with no model call. The NAL truth functions are
 *    deterministic; a pass that only runs them is reproducible from its premises alone.
 *  - [model] — a model guided it. The id is the one the roster and ModelMux use, so a receipt
 *    joins back to the mux session, the key that paid for it, and the quota it drew on.
 *  - [human] — a person asserted or adjudicated it. NARS' authority ladder already treats a user
 *    assertion as worth a thousand machine observations; this is who that user was.
 *
 * The distinction is not decorative. A belief induced under model guidance is not reproducible by
 * replaying the premises, so anything auditing the manifold has to know which derivations it can
 * recompute and which it can only cite.
 */
data class Evaluator(val kind: Kind, val id: String) {
    enum class Kind { PURE, MODEL, HUMAN }

    /** Stable across processes: the CID is over the kind and id, never an object identity. */
    val cid: ContentId = ContentId.of("${kind.name.lowercase()}:$id".encodeToByteArray())

    /** True when replaying the premises through the NAL functions must reproduce this belief. */
    val reproducible: Boolean get() = kind == Kind.PURE

    override fun toString(): String = "${kind.name.lowercase()}:$id"

    companion object {
        /** An algorithm with no model call — the id names the pass, as the constants used to. */
        fun pure(pass: String): Evaluator = Evaluator(Kind.PURE, pass)

        /** A model, named as the roster names it (e.g. "anthropic/claude-sonnet-4.5", "zai/glm-5"). */
        fun model(id: String): Evaluator = Evaluator(Kind.MODEL, id)

        /** A person. */
        fun human(id: String): Evaluator = Evaluator(Kind.HUMAN, id)
    }
}
