package borg.trikeshed.narsese

import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.SeriesBuffer
import borg.trikeshed.lib.contains
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α

/**
 * CURATION PROVENANCE AS PLANE FACTS. One immutable [DocumentCurationRecord] projects into the
 * `documents` partition under its curation cid: the model's proposals, the retained receipt of
 * every pipeline stage (success, failure and skip alike), the parser's rule candidates, and the
 * exact ModelMux receipt. Nothing here is a page: the Rete plane is the read surface
 * (`/api/rete/facts?partition=documents&field=curationCid&value=`), [PlaneFacts.toTriples] is
 * the RDF, the KIF tee is the bank, and the board draws the summary key and the stage keys.
 *
 * Every fact carries [PlaneFacts.KEY] pointing back at the board key that summarises it, so a
 * production or a reader can walk fact → key → fact without a second vocabulary.
 */
object DocumentCurationFacts {
    const val PARTITION = "documents"
    const val PROPOSAL = "document-proposal"
    const val STAGE_RECEIPT = "document-stage-receipt"
    const val RULE_CANDIDATE = "document-rule-candidate"
    const val MODEL_RECEIPT = "document-model-receipt"
    const val RULE_ADMISSION = "nal.rule.admit"
    const val ACTOR = "document"

    fun summaryKey(cid: ContentId): String = "document/curation/${cid.hex}"

    fun stageKey(record: DocumentCurationRecord, receipt: DocumentToolReceipt): String =
        "document/stage/${record.source.correlation}/${receipt.stage.name.lowercase()}"

    /** The live head judgement is the wire's; the projection only records what it was told. */
    data class SourceHead(val id: String?, val cid: String?, val current: Boolean?)

    /** Every fact of one record, in one series, so `rete.replace` under `curationCid` swaps them atomically. */
    fun facts(cid: ContentId, record: DocumentCurationRecord, head: SourceHead = SourceHead(null, null, null)): Series<ReteStoredFact> {
        val out = SeriesBuffer<ReteStoredFact>()
        for (f in proposalFacts(cid, record, head).view) out.add(f)
        for (f in stageFacts(cid, record).view) out.add(f)
        for (f in ruleFacts(cid, record).view) out.add(f)
        modelFact(cid, record)?.let(out::add)
        return out.drain()
    }

    fun proposalFacts(cid: ContentId, record: DocumentCurationRecord, head: SourceHead): Series<ReteStoredFact> {
        val tuples = record.proposals.values().filter { it.subject != null && it.predicate != null && it.obj != null }
        val grounding = record.toolReceipts.view.firstOrNull { it.stage == DocumentToolStage.GROUNDING }?.receiptCid?.value
        return tuples.size j { ordinal: Int ->
            val proposal = tuples[ordinal]
            PlaneFacts.fact(PARTITION, "${cid.hex}/$ordinal", mapOf(
                PlaneFacts.KIND to PROPOSAL, PlaneFacts.KEY to summaryKey(cid), PlaneFacts.ACTOR to ACTOR,
                "curationCid" to cid.value,
                "originalCid" to record.source.originalCid.value, "textCid" to record.source.extractedTextCid.value,
                "sourceRecordId" to head.id, "sourceRecordCid" to head.cid, "sourceCurrent" to head.current,
                "proposalCid" to proposal.receiptCid?.value, "predicate" to proposal.predicate,
                "subject" to proposal.subject, "object" to proposal.obj, "quote" to proposal.quote,
                "begin" to proposal.begin, "end" to proposal.end,
                "polarity" to proposal.polarity, "modality" to proposal.modality, "modelConfidence" to proposal.confidence,
                "quotationReceiptCid" to proposal.quotationReceiptCid?.value,
                "quotationBegin" to proposal.quotationBegin, "quotationEnd" to proposal.quotationEnd,
                "groundingReasons" to proposal.reasons.values(),
                "stageReceiptCid" to grounding,
                "submitted" to (proposal.receiptCid in record.submittedReceiptCids),
                "quotationSubmitted" to (proposal.quotationReceiptCid in record.quotationSubmittedReceiptCids),
            ))
        }
    }

    /** A stage that failed or was skipped is a fact with its error, never a missing row. */
    fun stageFacts(cid: ContentId, record: DocumentCurationRecord): Series<ReteStoredFact> =
        record.toolReceipts.size j { ordinal: Int ->
            val receipt = record.toolReceipts[ordinal]
            PlaneFacts.fact(PARTITION, "${cid.hex}/stage/$ordinal", stageFields(cid, record, receipt))
        }

    fun stageFields(cid: ContentId, record: DocumentCurationRecord, receipt: DocumentToolReceipt): Map<String, Any?> {
        val fields = linkedMapOf<String, Any?>(
            PlaneFacts.KIND to STAGE_RECEIPT, PlaneFacts.KEY to stageKey(record, receipt), PlaneFacts.ACTOR to ACTOR,
            "curationCid" to cid.value, "correlation" to record.source.correlation,
            "originalCid" to record.source.originalCid.value, "textCid" to record.source.extractedTextCid.value,
            "stage" to receipt.stage.name, "tool" to receipt.tool, "implementation" to receipt.implementation,
            "status" to receipt.status.name, "startedAt" to receipt.startedAt, "completedAt" to receipt.completedAt,
            "error" to receipt.error, "receiptCid" to receipt.receiptCid?.value,
            "inputCids" to receipt.inputCids.values { it.value }, "outputCids" to receipt.outputCids.values { it.value },
        )
        for ((k, v) in receipt.configuration) fields["configuration.$k"] = v
        return fields
    }

    /** The board value of one stage key: the receipt itself, so the key IS the provenance. */
    fun stageValue(cid: ContentId, record: DocumentCurationRecord, receipt: DocumentToolReceipt): Map<String, Any?> =
        stageFields(cid, record, receipt) - PlaneFacts.KIND - PlaneFacts.KEY - PlaneFacts.ACTOR

    /** Parser candidates stay candidates: the fact names the admission that would make one a rule. */
    fun ruleFacts(cid: ContentId, record: DocumentCurationRecord): Series<ReteStoredFact> {
        val axioms = record.nlpAxioms
        return axioms.size j { ordinal: Int ->
            val axiom = axioms[ordinal]
            val rule = axiom.rule
            PlaneFacts.fact(PARTITION, "${cid.hex}/rule/$ordinal", mapOf(
                PlaneFacts.KIND to RULE_CANDIDATE, PlaneFacts.KEY to summaryKey(cid), PlaneFacts.ACTOR to ACTOR,
                "curationCid" to cid.value, "originalCid" to record.source.originalCid.value,
                "ruleCid" to rule.ruleCid.value, "antecedent" to rule.antecedent, "consequent" to rule.consequent,
                "copula" to rule.copula.name, "symbol" to rule.copula.symbol, "eternal" to rule.isEternal,
                "positiveEvidence" to rule.evidence.positive, "negativeEvidence" to rule.evidence.negative,
                "provenanceCid" to rule.provenanceCid,
                "sentenceIndex" to axiom.sentenceIndex, "begin" to axiom.begin, "end" to axiom.end,
                "quote" to quote(record.source.text, axiom.begin, axiom.end),
                "predicate" to axiom.predicate, "patternWeight" to axiom.confidence.toDouble(),
                "admission" to RULE_ADMISSION,
            ))
        }
    }

    /** The exact receipt ModelMux minted for this record's call, when one was retained. */
    fun modelFact(cid: ContentId, record: DocumentCurationRecord): ReteStoredFact? {
        val receipt = record.receipt ?: return null
        return PlaneFacts.fact(PARTITION, "${cid.hex}/model", mapOf(
            PlaneFacts.KIND to MODEL_RECEIPT, PlaneFacts.KEY to summaryKey(cid), PlaneFacts.ACTOR to ACTOR,
            "curationCid" to cid.value, "originalCid" to record.source.originalCid.value,
        ) + DocumentCuratorCodec.modelReceipt(receipt))
    }

    /**
     * Rows shaped exactly as `nal.rule.admit` reads its `rules` input (`antecedent`, `consequent`,
     * `copula`), carrying the candidate's identity beside them. No `discount` is set: the
     * extraction weight is not parser confidence, so the weight is the admitting operator's call.
     */
    fun ruleCandidates(record: DocumentCurationRecord): Series<Map<String, Any?>> = record.nlpAxioms α { axiom ->
        val rule = axiom.rule
        mapOf(
            "antecedent" to rule.antecedent, "consequent" to rule.consequent, "copula" to rule.copula.symbol,
            "ruleCid" to rule.ruleCid.value, "provenanceCid" to rule.provenanceCid,
            "sentenceIndex" to axiom.sentenceIndex, "begin" to axiom.begin, "end" to axiom.end,
            "quote" to quote(record.source.text, axiom.begin, axiom.end),
            "patternWeight" to axiom.confidence.toDouble(), "admission" to RULE_ADMISSION,
        )
    }

    /** The board summary: counts a reader can act on, and the stage receipt cids as the way into the plane. */
    fun summary(cid: ContentId, record: DocumentCurationRecord): Map<String, Any?> = mapOf(
        "receiptCid" to cid.value,
        "name" to record.source.name, "originalCid" to record.source.originalCid.value,
        "textCid" to record.source.extractedTextCid.value, "correlation" to record.source.correlation,
        "model" to record.model?.modelId,
        "proposals" to record.proposals.size, "submitted" to record.submittedReceiptCids.size,
        "quotationsSubmitted" to record.quotationSubmittedReceiptCids.size,
        "stages" to record.toolReceipts.size, "rules" to record.nlpAxioms.size,
        "stageReceiptCids" to record.toolReceipts.values { it.receiptCid?.value },
        "modelReceiptId" to record.receipt?.receiptId,
        "reasons" to record.reasons.values(),
    )

    /** Internal, not private: the inlined `α` body above calls it from a generated class, and a private member there fails JVM verification. */
    internal fun quote(text: String, begin: Int, end: Int): String? =
        if (begin in 0..end && end <= text.length) text.substring(begin, end) else null
}
