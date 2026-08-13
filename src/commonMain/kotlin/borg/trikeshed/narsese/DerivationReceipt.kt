package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

@JvmInline
value class TermIdentity(val id: Long)

enum class RuleIdentity {
    DEDUCTION
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
    }
}
