package borg.trikeshed.narsese

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * TurnReviewElement — Hermes' per-turn background_review, re-based host-side.
 *
 * The review logic runs over facts the pen ALREADY landed — no second agent
 * process, no prefix-cache fork. The PURE pass is quota-free by construction
 * (no model call) and can therefore never starve:
 *
 *  1. per-fact OBSERVATION — "context C → tool/skill S" outcome evidence;
 *  2. per-turn INDUCTION — facts sharing the turn's context generalize
 *     pairwise (M→P, M→S ⊢ S→P) with NAL-weak evidence;
 *  3. intake cap = 16 per turn (Hermes' iteration cap reborn as a budget).
 *
 * An optional LLM pass (mux_converse on a cheaper lease class) can add richer
 * signals through the same intake path — it is NOT this element's concern;
 * callers feed its conclusions back through [reviewTurn] like any other facts.
 *
 * DRAINING semantics: the next live turn calls [drain]; in-flight intakes
 * complete (the bag's channel is the serial spine), nothing is hard-cancelled.
 */
class TurnReviewElement(
    private val bag: BeliefBagElement,
    private val intakeCap: Int = 16,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<TurnReviewElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    /** One landed pen fact, boundary-shaped for review. */
    data class TurnFact(
        val verb: String,
        val ok: Boolean,
        val contextTerm: String,
        val objectTerm: String? = null,
    )

    private val evaluator = ContentId.of("turn-review".encodeToByteArray())

    override suspend fun open() {
        super.open()
        if (state == ElementState.OPEN) state = ElementState.ACTIVE
    }

    /**
     * The pure induction pass. Returns the (angular → gloss) pairs landed
     * (≤ [intakeCap]) so a render layer can caption the minted beliefs.
     * Quota-free: no model call anywhere on this path.
     */
    suspend fun reviewTurn(facts: List<TurnFact>, turnSucceeded: Boolean): List<Pair<Long, String>> {
        if (state != ElementState.ACTIVE) return emptyList()
        val landed = ArrayList<Pair<Long, String>>()

        // 1. per-fact observation: (context → object|verb) outcome evidence
        for (fact in facts) {
            if (landed.size >= intakeCap) break
            val subject = fact.contextTerm
            val obj = fact.objectTerm ?: fact.verb
            val angular = AngularCodec.encode(
                relation = RelationKind.CAUSALITY,
                taxonomyKey = "review",
                subjectTerm = subject,
                objectTerm = obj,
            )
            val receipt = DerivationReceipt.observation(
                subject = TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = subject)),
                predicate = TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = obj)),
                contextCid = ContentId.of(subject.encodeToByteArray()),
                outcomeCid = ContentId.of("${fact.ok && turnSucceeded}".encodeToByteArray()),
                evidence = Nal.observe(fact.ok && turnSucceeded),
                evaluatorCid = evaluator,
            )
            bag.intake.send(
                BeliefIntake.Mint(
                    SemanticSignal(
                        angular = angular,
                        evidence = receipt.evidence,
                        relation = RelationKind.CAUSALITY,
                        subjectCid = ContentId.of(subject.encodeToByteArray()).value,
                        objectCid = ContentId.of(obj.encodeToByteArray()).value,
                        provenanceCid = receipt.canonicalCid.value,
                    ),
                    BudgetCoord(0.7f, 0.4f, 0.6f),
                    receiptCid = receipt.canonicalCid,
                    gloss = "$subject → $obj (${if (fact.ok && turnSucceeded) "worked" else "failed"})",
                ),
            )
            landed.add(angular to "$subject → $obj (${if (fact.ok && turnSucceeded) "worked" else "failed"})")
        }

        // 2. per-turn induction: same-context facts generalize pairwise (weak evidence)
        val byContext = facts.groupBy { it.contextTerm }
        outer@ for ((context, group) in byContext) {
            if (group.size < 2) continue
            val truths = group.map { TruthCoord(if (it.ok) 1f else 0f, 0.9f) }
            for (i in group.indices) for (jj in i + 1 until group.size) {
                if (landed.size >= intakeCap) break@outer
                val a = group[i].objectTerm ?: group[i].verb
                val b = group[jj].objectTerm ?: group[jj].verb
                if (a == b) continue
                val evidence = Nal.induce(truths[i], truths[jj])
                if (evidence.total == 0L) continue
                val p1 = PremiseReceipt(
                    ContentId.of("$context->$a".encodeToByteArray()),
                    TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = context)),
                    TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = a)),
                )
                val p2 = PremiseReceipt(
                    ContentId.of("$context->$b".encodeToByteArray()),
                    TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = context)),
                    TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = b)),
                )
                val receipt = DerivationReceipt.induction(p1, p2, evidence, evaluator)
                val inducedAngular = AngularCodec.encode(
                    relation = RelationKind.ATTRACTION,
                    taxonomyKey = "review/induced",
                    subjectTerm = b,
                    objectTerm = a,
                )
                bag.intake.send(
                    BeliefIntake.Mint(
                        SemanticSignal(
                            angular = inducedAngular,
                            evidence = evidence,
                            relation = RelationKind.ATTRACTION,
                            subjectCid = ContentId.of(b.encodeToByteArray()).value,
                            objectCid = ContentId.of(a.encodeToByteArray()).value,
                            provenanceCid = receipt.canonicalCid.value,
                        ),
                        BudgetCoord(0.4f, 0.3f, 0.5f),
                        receiptCid = receipt.canonicalCid,
                        gloss = "$b tends to accompany $a (induced from $context)",
                    ),
                )
                landed.add(inducedAngular to "$b tends to accompany $a (induced from $context)")
            }
        }
        return landed
    }
}
