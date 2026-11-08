package borg.trikeshed.narsese

import borg.trikeshed.parse.confix.CborLimitException
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId

/** Exact, untruncated claim captured by the host from one live proposal fact. */
internal data class DocumentQuotationClaim(
    val curationCid: ContentId,
    val originalCid: ContentId,
    val extractedTextCid: ContentId,
    val quotationReceiptCid: ContentId,
    val proposalCid: ContentId?,
    val subject: String,
    val predicate: String,
    val obj: String,
    val polarity: Boolean?,
    val modality: String?,
    val quote: String,
    val quotationBegin: Int,
    val quotationEnd: Int,
)

internal enum class DocumentQuotationStatus {
    EXACT_EXTRACTED_SPAN, UNAVAILABLE, LIMIT, INTEGRITY_MISMATCH, MALFORMED, AMBIGUOUS, RECEIPT_MISMATCH,
}

/**
 * Pure, opt-in document-proposal consistency check over already-retrieved bytes. This does
 * not perform CAS or network access, authorization, review, source authentication,
 * currentness, OCR validation, query-witness tracking, or legal inference. It does not handle
 * book-curation receipts. The original document bytes are not supplied or checked. A caller
 * must authorize and bound reads before allocating bytes,
 * and capture complete, nonconflicting claim fields from the live fact snapshot.
 */
internal object DocumentQuotationCheck {
    private const val MAX_RECORD_BYTES = 16 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 16 * 1024 * 1024
    private const val MAX_RECEIPT_BYTES = 64 * 1024
    private const val MAX_PROPOSALS = 10_000
    private const val MAX_CLAIM_CHARS = 32_768
    private const val MAX_ITEMS = 1_000_000

    fun verify(claim: DocumentQuotationClaim, curationBytes: ByteArray?, extractedBytes: ByteArray?,
        quotationBytes: ByteArray?): DocumentQuotationStatus {
        if (curationBytes == null || extractedBytes == null || quotationBytes == null)
            return DocumentQuotationStatus.UNAVAILABLE
        if (curationBytes.size > MAX_RECORD_BYTES || extractedBytes.size > MAX_TEXT_BYTES ||
            quotationBytes.size > MAX_RECEIPT_BYTES || claim.quote.length > MAX_CLAIM_CHARS ||
            claim.subject.length > MAX_CLAIM_CHARS || claim.predicate.length > MAX_CLAIM_CHARS ||
            claim.obj.length > MAX_CLAIM_CHARS || (claim.modality?.length ?: 0) > MAX_CLAIM_CHARS)
            return DocumentQuotationStatus.LIMIT

        // Freeze mutable caller-owned bytes before digesting or decoding them.
        val recordBytes = curationBytes.copyOf()
        val textBytes = extractedBytes.copyOf()
        val receiptBytes = quotationBytes.copyOf()
        if (ContentId.of(recordBytes) != claim.curationCid || ContentId.of(textBytes) != claim.extractedTextCid ||
            ContentId.of(receiptBytes) != claim.quotationReceiptCid) return DocumentQuotationStatus.INTEGRITY_MISMATCH
        val record = try { CanonicalCbor.decodeMapBounded(recordBytes, MAX_RECORD_BYTES, maxItems = MAX_ITEMS) }
            catch (_: CborLimitException) { return DocumentQuotationStatus.LIMIT }
            catch (_: Exception) { return DocumentQuotationStatus.MALFORMED }
        val text = try { textBytes.decodeToString(throwOnInvalidSequence = true) }
            catch (_: Exception) { return DocumentQuotationStatus.MALFORMED }
        // Inspect raw CBOR types. The existing full-record decoder coerces Number.toInt(),
        // so it must not be used here: huge integers can wrap and floats can truncate.
        if (record["version"] !is Long || record["version"] != 1L) return DocumentQuotationStatus.MALFORMED
        val source = record["source"] as? Map<*, *> ?: return DocumentQuotationStatus.MALFORMED
        val original = source["originalCid"] as? String ?: return DocumentQuotationStatus.MALFORMED
        val extracted = source["extractedTextCid"] as? String ?: return DocumentQuotationStatus.MALFORMED
        val sourceText = source["text"] as? String ?: return DocumentQuotationStatus.MALFORMED
        if (original != claim.originalCid.value || extracted != claim.extractedTextCid.value || sourceText != text)
            return DocumentQuotationStatus.INTEGRITY_MISMATCH
        val proposals = record["proposals"] as? List<*> ?: return DocumentQuotationStatus.MALFORMED
        if (proposals.size > MAX_PROPOSALS) return DocumentQuotationStatus.LIMIT
        var matches = 0
        for (raw in proposals) {
            val proposal = raw as? Map<*, *> ?: return DocumentQuotationStatus.MALFORMED
            val quotationId = proposal["quotationReceiptCid"]
            if (quotationId == null) continue // Older/unlocated proposals have no quotation receipt.
            if (quotationId !is String) return DocumentQuotationStatus.MALFORMED
            if (quotationId != claim.quotationReceiptCid.value) continue
            val begin = proposal["quotationBegin"] ?: return DocumentQuotationStatus.UNAVAILABLE
            val end = proposal["quotationEnd"] ?: return DocumentQuotationStatus.UNAVAILABLE
            if (begin !is Long || end !is Long || begin < 0L || end <= begin ||
                end > Int.MAX_VALUE.toLong() || end > text.length.toLong()) return DocumentQuotationStatus.MALFORMED
            val subject = proposal["subject"] as? String ?: return DocumentQuotationStatus.MALFORMED
            val predicate = proposal["predicate"] as? String ?: return DocumentQuotationStatus.MALFORMED
            val obj = proposal["object"] as? String ?: return DocumentQuotationStatus.MALFORMED
            val quote = proposal["quote"] as? String ?: return DocumentQuotationStatus.MALFORMED
            val polarity = proposal["polarity"]
            val modality = proposal["modality"]
            val proposalId = proposal["receiptCid"]
            if (polarity != null && polarity !is Boolean || modality != null && modality !is String ||
                proposalId != null && proposalId !is String) return DocumentQuotationStatus.MALFORMED
            if (proposalId != claim.proposalCid?.value || subject != claim.subject || predicate != claim.predicate ||
                obj != claim.obj || polarity != claim.polarity || modality != claim.modality || quote != claim.quote ||
                begin.toInt() != claim.quotationBegin || end.toInt() != claim.quotationEnd) continue
            matches++
            if (matches > 1) return DocumentQuotationStatus.AMBIGUOUS
        }
        if (matches == 0) return DocumentQuotationStatus.UNAVAILABLE
        val begin = claim.quotationBegin
        val end = claim.quotationEnd
        if (!exactSpan(text, begin, end, claim.quote)) return DocumentQuotationStatus.INTEGRITY_MISMATCH

        // Only fields used by quotationReceipt are reconstructed. No model offsets are repaired.
        val retainedSource = DocumentSource(claim.originalCid, claim.extractedTextCid, text, "", "", "")
        val retainedProposal = DocumentProposal("", subject = claim.subject, quote = claim.quote,
            quotationBegin = begin, quotationEnd = end)
        val expectedReceipt = try { DocumentCuratorGrounding.quotationReceipt(retainedSource, retainedProposal) }
            catch (_: Exception) { return DocumentQuotationStatus.MALFORMED }
        // A valid CID with different bytes could be an unsupported future receipt format, not proof of tampering.
        if (!expectedReceipt.contentEquals(receiptBytes)) return DocumentQuotationStatus.RECEIPT_MISMATCH
        return DocumentQuotationStatus.EXACT_EXTRACTED_SPAN
    }

    /** Offsets are UTF-16, but cannot split a supplementary character. */
    internal fun exactSpan(text: String, begin: Int, end: Int, quote: String): Boolean {
        fun splitsSurrogate(at: Int): Boolean = at > 0 && at < text.length &&
            text[at - 1] in '\uD800'..'\uDBFF' && text[at] in '\uDC00'..'\uDFFF'
        return begin >= 0 && end > begin && end <= text.length && !splitsSurrogate(begin) &&
            !splitsSurrogate(end) && text.substring(begin, end) == quote
    }
}
