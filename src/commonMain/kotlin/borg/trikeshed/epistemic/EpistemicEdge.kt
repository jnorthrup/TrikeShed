package borg.trikeshed.epistemic

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.TruthCoord
import borg.trikeshed.ontology.SumoOntology
import borg.trikeshed.ontology.SumoOntology.SumoCategory

/**
 * The Epistemic Edge — one fact as a Join of semantic geometry and evidential weight.
 *
 * SUMO, NARS and Rete were three engines joined pairwise: `KgNalBridge` carried KIF predicates
 * onto NAL copulas, `CausalityRete` admitted eternal truths to a rete, and a `ReteFact` carried
 * neither a truth value nor an ontological coordinate. Three coordinate systems with two ladders
 * between them is not a manifold, and nothing could state a fact in all three at once.
 *
 * This is the missing type, and it is a [Join] like everything else:
 *
 *     EpistemicEdge = Join<OntologicalPath, TruthCoord>
 *
 * The left side says *what* the relation is and is checked against the SUMO hierarchy. The right
 * side says how true it is and how much evidence stands behind it. Neither half is optional: a
 * coordinate with no truth is a schema, and a truth with no coordinate is a number.
 */
typealias EpistemicEdge = Join<OntologicalPath, TruthCoord>

/**
 * A coordinate in the upper-merged ontology: subject, relation, object.
 *
 * [relation] is itself a SUMO category, so a path is three points in one hierarchy rather than
 * two points and a string. That is what makes [OntologicalPath.admits] a question about the
 * ontology instead of a naming convention.
 */
data class OntologicalPath(
    val subject: SumoCategory,
    val relation: SumoCategory,
    val `object`: SumoCategory,
) {
    /** `(relation subject object)` — the KIF form, so a path can be written to the KIF ledger. */
    fun kif(): String = "(${relation.kifName} ${subject.kifName} ${`object`.kifName})"

    override fun toString(): String = "${subject.kifName} --${relation.kifName}--> ${`object`.kifName}"

    companion object {
        /**
         * Whether this path is structurally admissible.
         *
         * The spec's claim is that invalid topological joins are impossible rather than merely
         * wrong — joining a spatial region to a temporal interval through an agency relation should
         * not be representable. A relation must be a Relation (or under it), and the endpoints must
         * be Entities: `Relation --AgentOf--> Process` is refused because a relation is not an
         * entity that can stand at the end of one.
         */
        fun admits(subject: SumoCategory, relation: SumoCategory, `object`: SumoCategory): Boolean {
            val relationIsRelation = relation == SumoCategory.Relation ||
                SumoOntology.isSubclass(relation, SumoCategory.Relation)
            if (!relationIsRelation) return false
            return isEntity(subject) && isEntity(`object`)
        }

        private fun isEntity(c: SumoCategory): Boolean =
            c != SumoCategory.Relation &&
                !SumoOntology.isSubclass(c, SumoCategory.Relation) &&
                (c == SumoCategory.Entity || SumoOntology.isSubclass(c, SumoCategory.Entity))
    }
}

/** Construct an edge, refusing a path the ontology does not admit. */
fun epistemicEdge(
    subject: SumoCategory,
    relation: SumoCategory,
    `object`: SumoCategory,
    truth: TruthCoord,
): EpistemicEdge {
    require(OntologicalPath.admits(subject, relation, `object`)) {
        "the ontology does not admit ${subject.kifName} --${relation.kifName}--> ${`object`.kifName}"
    }
    return OntologicalPath(subject, relation, `object`) j truth
}

val EpistemicEdge.path: OntologicalPath get() = a
val EpistemicEdge.truth: TruthCoord get() = b

/**
 * The β-join: two edges that meet at a term compose into a third.
 *
 * This is the operation the three engines could not previously express together. Rete decides
 * *whether* two edges intersect (geometry), the SUMO hierarchy decides whether the conclusion is
 * admissible (structure), and the NAL truth functions decide what the conclusion is worth
 * (evidence). One call, three axes.
 *
 * Which NAL figure applies is read off *where* the edges meet, exactly as NAL defines it:
 *
 *  - deduction  `M→P`, `S→M` ⊢ `S→P` — this edge's subject is the other's object
 *  - abduction  `P→M`, `S→M` ⊢ `S→P` — the two share an object
 *  - induction  `M→P`, `M→S` ⊢ `S→P` — the two share a subject
 *
 * Returns null when the edges do not meet, when the relations differ (two different relations do
 * not compose), or when the conclusion is a path the ontology refuses. Null is a real answer: it
 * is the manifold having no gradient there.
 */
fun EpistemicEdge.compose(other: EpistemicEdge, k: Float = 1f): EpistemicEdge? {
    val p = path
    val q = other.path
    if (p.relation != q.relation) return null

    val (subject, obj, evidence) = when {
        // M→P (this), S→M (other) ⊢ S→P
        p.subject == q.`object` -> Triple(q.subject, p.`object`, Nal.deduce(truth, other.truth, k))
        // P→M (this), S→M (other) ⊢ S→P
        p.`object` == q.`object` && p.subject != q.subject ->
            Triple(q.subject, p.subject, Nal.abduce(truth, other.truth))
        // M→P (this), M→S (other) ⊢ S→P
        p.subject == q.subject && p.`object` != q.`object` ->
            Triple(q.`object`, p.`object`, Nal.induce(truth, other.truth))
        else -> return null
    }
    if (!OntologicalPath.admits(subject, p.relation, obj)) return null
    // Nal.truthOf, NOT EvidenceCoord.truth(): Nal mints evidence in milli-units, so the plain
    // accessor reads a derived conclusion as thousands of observations and confidence saturates.
    // A first draft here composed two premises of c=0.9 into a conclusion of c=0.999, which NAL
    // forbids -- deduction is strong but never strengthens.
    return OntologicalPath(subject, p.relation, obj) j Nal.truthOf(evidence, k)
}

/**
 * Two edges on the same path whose expectations sit on opposite sides of 0.5, both held with at
 * least [confidenceFloor]. This is the singularity the spec's tribunal adjudicates: not merely a
 * disagreement, but one the system is confident about in both directions.
 */
fun EpistemicEdge.contradicts(other: EpistemicEdge, confidenceFloor: Float = 0.9f): Boolean {
    if (path != other.path) return false
    if (truth.confidence < confidenceFloor || other.truth.confidence < confidenceFloor) return false
    val a = truth.expectation()
    val b = other.truth.expectation()
    return (a - 0.5f) * (b - 0.5f) < 0f
}
