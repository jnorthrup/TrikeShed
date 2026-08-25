package borg.trikeshed.narsese

import kotlin.jvm.JvmInline

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

@JvmInline
value class TermIdentity(val id: Long)

enum class RuleIdentity {
    DEDUCTION,

    /** Weak rule: M→P, M→S ⊢ S→P — shared-subject generalization. */
    INDUCTION,

    /** Weak rule: P→M, S→M ⊢ S→P — shared-predicate explanation. */
    ABDUCTION,

    /** Same-statement evidence-base merge (the reviseInto fold, receipted). */
    REVISION,

    /** Single-premise: one observed outcome in a context — the per-turn review primitive. */
    OBSERVATION,
}

class PremiseReceipt(
    val cid: ContentId,
    val subject: TermIdentity,
    val predicate: TermIdentity
)

class DerivationReceipt(
    val ruleId: RuleIdentity,
    val conclusionSubject: TermIdentity,
    val conclusionPredicate: TermIdentity,
    val premises: Join<ContentId, ContentId>,
    val evidence: EvidenceCoord,
    val evaluatorCid: ContentId
) {
    val canonicalCid: ContentId

    init {
        // Deterministic length-delimited canonical serialization
        // format:
        // ruleId.name.length:ruleId.name;
        // conclusionSubject.id;
        // conclusionPredicate.id;
        // premises.a.hex;
        // premises.b.hex;
        // evidence.packed;
        // evaluatorCid.hex;

        val ruleStr = ruleId.name
        val builder = StringBuilder()
        builder.append(ruleStr.length).append(":").append(ruleStr).append(";")
        builder.append(conclusionSubject.id).append(";")
        builder.append(conclusionPredicate.id).append(";")
        builder.append(premises.a.hex).append(";")
        builder.append(premises.b.hex).append(";")
        builder.append(evidence.packed).append(";")
        builder.append(evaluatorCid.hex).append(";")

        canonicalCid = ContentId.of(builder.toString().encodeToByteArray())
    }

    companion object {
        fun deduction(
            premise1: PremiseReceipt,
            premise2: PremiseReceipt,
            evidence: EvidenceCoord,
            evaluatorCid: ContentId
        ): DerivationReceipt {
            require(premise1.predicate == premise2.subject) {
                "Mismatched middle terms in deduction: ${premise1.predicate} != ${premise2.subject}"
            }
            return DerivationReceipt(
                ruleId = RuleIdentity.DEDUCTION,
                conclusionSubject = premise1.subject,
                conclusionPredicate = premise2.predicate,
                premises = premise1.cid j premise2.cid,
                evidence = evidence,
                evaluatorCid = evaluatorCid
            )
        }

        /** M→P (p1), M→S (p2) ⊢ S→P — requires the shared subject M. */
        fun induction(
            premise1: PremiseReceipt,
            premise2: PremiseReceipt,
            evidence: EvidenceCoord,
            evaluatorCid: ContentId
        ): DerivationReceipt {
            require(premise1.subject == premise2.subject) {
                "Mismatched shared subject in induction: ${premise1.subject} != ${premise2.subject}"
            }
            return DerivationReceipt(
                ruleId = RuleIdentity.INDUCTION,
                conclusionSubject = premise2.predicate,
                conclusionPredicate = premise1.predicate,
                premises = premise1.cid j premise2.cid,
                evidence = evidence,
                evaluatorCid = evaluatorCid
            )
        }

        /** P→M (p1), S→M (p2) ⊢ S→P — requires the shared predicate M. */
        fun abduction(
            premise1: PremiseReceipt,
            premise2: PremiseReceipt,
            evidence: EvidenceCoord,
            evaluatorCid: ContentId
        ): DerivationReceipt {
            require(premise1.predicate == premise2.predicate) {
                "Mismatched shared predicate in abduction: ${premise1.predicate} != ${premise2.predicate}"
            }
            return DerivationReceipt(
                ruleId = RuleIdentity.ABDUCTION,
                conclusionSubject = premise2.subject,
                conclusionPredicate = premise1.subject,
                premises = premise1.cid j premise2.cid,
                evidence = evidence,
                evaluatorCid = evaluatorCid
            )
        }

        /** Same statement, two evidence bases — evidence = revise(e1, e2), receipted. */
        fun revision(
            premise1: PremiseReceipt,
            premise2: PremiseReceipt,
            evidence1: EvidenceCoord,
            evidence2: EvidenceCoord,
            evaluatorCid: ContentId
        ): DerivationReceipt {
            require(premise1.subject == premise2.subject && premise1.predicate == premise2.predicate) {
                "Revision requires the same statement: " +
                    "(${premise1.subject},${premise1.predicate}) != (${premise2.subject},${premise2.predicate})"
            }
            return DerivationReceipt(
                ruleId = RuleIdentity.REVISION,
                conclusionSubject = premise1.subject,
                conclusionPredicate = premise1.predicate,
                premises = premise1.cid j premise2.cid,
                evidence = revise(evidence1, evidence2),
                evaluatorCid = evaluatorCid
            )
        }

        /**
         * Single-premise observation: an outcome observed in a context. The premise
         * pair carries (contextCid ⋈ outcomeCid); the statement is given directly.
         * This is the per-turn review primitive — accumulate by reviseInto.
         */
        fun observation(
            subject: TermIdentity,
            predicate: TermIdentity,
            contextCid: ContentId,
            outcomeCid: ContentId,
            evidence: EvidenceCoord,
            evaluatorCid: ContentId
        ): DerivationReceipt = DerivationReceipt(
            ruleId = RuleIdentity.OBSERVATION,
            conclusionSubject = subject,
            conclusionPredicate = predicate,
            premises = contextCid j outcomeCid,
            evidence = evidence,
            evaluatorCid = evaluatorCid
        )
    }
}
