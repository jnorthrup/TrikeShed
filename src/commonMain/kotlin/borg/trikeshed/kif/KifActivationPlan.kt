package borg.trikeshed.kif

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

data class KifActivationSource(
    val nodeId: ContentId,
    /** Logical syntax identity, independent of source identity and snapshot indexing. */
    val assertionCid: ContentId,
    val source: KifSourceAssertion,
    val exclusion: String?,
)

data class KifActivationProof(
    val nodeId: ContentId,
    /** Ordered premise path; two independently sourced assertions remain two supports. */
    val premises: Series<ContentId>,
    val rules: Series<String>,
)

/**
 * Review/export contract for the existing positive ground taxonomy explanation.
 * [queryId] and proof/source node IDs are content identities. [frameId] additionally
 * binds the complete source snapshot, revision, policy, search limits and receipt. A new
 * revision creates a fresh frame; this contract promises no mutable invalidation.
 *
 * Proof nodes depend on source nodes; the query depends on alternative proof nodes.
 * Sources excluded from execution remain in [sources]. UNKNOWN is not refutation;
 * [truncated] means proofs may be incomplete even when [status] is SUPPORTED.
 * This is neither registered MLIR nor an invocation of mlnixRoundTrip/evaluation.
 */
class KifActivationPlan private constructor(
    val queryId: ContentId,
    val frameId: ContentId,
    val revision: Long,
    val predicate: String,
    val subject: String,
    val obj: String,
    val semantics: String,
    val status: KifExplanationStatus,
    val truncated: Boolean,
    val steps: Int,
    val limits: KifActivationLimits,
    val sources: Series<KifActivationSource>,
    val proofs: Series<KifActivationProof>,
) {
    /**
     * Versioned length-framed UTF-8 export, NOT MLIR text. Every field is a decimal
     * UTF-8 byte length, ':' and exactly those bytes. Nested sequences start with
     * their field-encoded decimal count. Hash domains use this same encoding.
     * Consumers must preserve status, truncation, source review and exclusions;
     * exporting a support receipt never grants source approval or host effects.
     */
    fun export(): ByteArray = buildString {
        fields("kif-activation-plan/v1", queryId.value, frameId.value, revision.toString(),
            predicate, subject, obj, semantics, status.name, truncated.toString(), steps.toString(),
            limits.maxAssertions.toString(), limits.maxTotalChars.toString(),
            limits.maxSteps.toString(), limits.maxProofs.toString(), sources.size.toString())
        for (entry in sources.view) {
            fields(entry.nodeId.value, entry.assertionCid.value, entry.source.id,
                entry.source.sourceCid.value, entry.source.sourceRevision, entry.source.review.name,
                entry.source.text, (entry.exclusion != null).toString(), entry.exclusion.orEmpty())
        }
        fields(proofs.size.toString())
        for (proof in proofs.view) {
            fields(proof.nodeId.value, proof.premises.size.toString())
            for (premise in proof.premises.view) fields(premise.value)
            fields(proof.rules.size.toString())
            for (rule in proof.rules.view) fields(rule)
        }
    }.encodeToByteArray()

    companion object {
        internal fun create(snapshot: KifTaxonomySnapshot, predicate: String, subject: String, obj: String): KifActivationPlan {
            val result = snapshot.explain(predicate, subject, obj)
            val queryId = identity("kif-query/v1", result.semantics, predicate, subject, obj)
            val sources = snapshot.assertions.sortedBy { it.source.id }.map { entry ->
                val source = entry.source
                KifActivationSource(identity("kif-source/v1", source.id, source.sourceCid.value,
                    source.sourceRevision, source.review.name, source.text, entry.assertionCid.value,
                    (entry.exclusion != null).toString(), entry.exclusion.orEmpty()),
                    entry.assertionCid, source, entry.exclusion)
            }.toSeries()
            val byId = sources.view.associateBy { it.source.id }
            val proofs = result.proofs.map { proof ->
                val premises = proof.assertionIds.toSeries() α { byId.getValue(it).nodeId }
                val rules = proof.ruleIds.toSeries()
                val nodeId = ContentId.of(buildString {
                    fields("kif-proof/v1", queryId.value, premises.size.toString())
                    for (premise in premises.view) fields(premise.value)
                    fields(rules.size.toString())
                    for (rule in rules.view) fields(rule)
                }.encodeToByteArray())
                KifActivationProof(nodeId, premises, rules)
            }.sortedBy { it.nodeId.value }.toSeries()
            val limits = snapshot.limits
            val frameId = ContentId.of(buildString {
                fields("kif-frame/v1", snapshot.revision.toString(), queryId.value, result.semantics,
                    limits.maxAssertions.toString(), limits.maxTotalChars.toString(),
                    limits.maxSteps.toString(), limits.maxProofs.toString(), sources.size.toString())
                for (source in sources.view) fields(source.nodeId.value)
                fields(result.status.name, result.truncated.toString(), result.steps.toString(), proofs.size.toString())
                for (proof in proofs.view) fields(proof.nodeId.value)
            }.encodeToByteArray())
            return KifActivationPlan(queryId, frameId, snapshot.revision, predicate, subject, obj,
                result.semantics, result.status, result.truncated, result.steps, limits, sources, proofs)
        }

        private fun identity(vararg fields: String): ContentId =
            ContentId.of(buildString { fields(*fields) }.encodeToByteArray())

        private fun StringBuilder.fields(vararg values: String) {
            for (value in values) append(value.encodeToByteArray().size).append(':').append(value)
        }
    }
}
