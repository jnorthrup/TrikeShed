package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import kotlin.test.*

class DocumentQuotationCheckTest {
    private data class Fixture(val claim: DocumentQuotationClaim, val record: ByteArray,
        val text: ByteArray, val quotation: ByteArray)

    private fun fixture(duplicate: Boolean = false): Fixture {
        val text = "A😀B\r\n"
        val textBytes = text.encodeToByteArray()
        val source = DocumentSource(ContentId.of("original".encodeToByteArray()), ContentId.of(textBytes), text,
            "source", "text/plain", "fixture")
        // The retained quotation offsets, not the original model offsets, determine the span.
        val seed = DocumentProposal("{}", "A", "states", "B", quote = "😀", begin = 0, end = 1,
            polarity = true, modality = "asserted", quotationBegin = 1, quotationEnd = 3)
        val quotation = DocumentCuratorGrounding.quotationReceipt(source, seed)
        val qcid = ContentId.of(quotation)
        val proposal = seed.copy(quotationReceiptCid = qcid)
        val record = DocumentCurationRecord(source, null, null, "fixture",
            if (duplicate) s_[proposal, proposal] else s_[proposal], emptySeriesOf())
        val bytes = DocumentCuratorCodec.encode(record)
        val claim = DocumentQuotationClaim(ContentId.of(bytes), source.originalCid, source.extractedTextCid,
            qcid, null, "A", "states", "B", true, "asserted", "😀", 1, 3)
        return Fixture(claim, bytes, textBytes, quotation)
    }

    private fun editRecord(f: Fixture, edit: (MutableMap<String, Any?>) -> Unit): Fixture {
        val record = CanonicalCbor.decodeMap(f.record).toMutableMap()
        edit(record)
        val bytes = CanonicalCbor.encodeMap(record)
        return f.copy(claim = f.claim.copy(curationCid = ContentId.of(bytes)), record = bytes)
    }

    private fun editProposal(f: Fixture, edit: (MutableMap<String, Any?>) -> Unit): Fixture = editRecord(f) { record ->
        val rows = (record["proposals"] as List<*>).map { row ->
            (row as Map<*, *>).entries.associate { (key, value) -> key as String to value }.toMutableMap()
        }
        edit(rows[0])
        record["proposals"] = rows
    }

    @Test fun verifiesOnlyRetainedUtf16SpanAndRejectsSurrogateSplits() {
        val f = fixture()
        assertEquals(DocumentQuotationStatus.EXACT_EXTRACTED_SPAN,
            DocumentQuotationCheck.verify(f.claim, f.record, f.text, f.quotation))
        val text = "A😀B"
        assertTrue(DocumentQuotationCheck.exactSpan(text, 1, 3, "😀"))
        assertFalse(DocumentQuotationCheck.exactSpan(text, 1, 2, text.substring(1, 2)))
        assertFalse(DocumentQuotationCheck.exactSpan(text, 2, 3, text.substring(2, 3)))
        assertFalse(DocumentQuotationCheck.exactSpan(text, -1, 3, ""))
    }

    @Test fun refusesMissingTamperedConflictingAndAmbiguousInputs() {
        val f = fixture()
        assertEquals(DocumentQuotationStatus.UNAVAILABLE, DocumentQuotationCheck.verify(f.claim, null, f.text, f.quotation))
        assertEquals(DocumentQuotationStatus.INTEGRITY_MISMATCH,
            DocumentQuotationCheck.verify(f.claim, f.record, f.text + _a[0.toByte()], f.quotation))
        assertEquals(DocumentQuotationStatus.INTEGRITY_MISMATCH,
            DocumentQuotationCheck.verify(f.claim, f.record, f.text, f.quotation + _a[0.toByte()]))
        assertEquals(DocumentQuotationStatus.INTEGRITY_MISMATCH,
            DocumentQuotationCheck.verify(f.claim.copy(originalCid = ContentId.of("other".encodeToByteArray())),
                f.record, f.text, f.quotation))
        assertEquals(DocumentQuotationStatus.UNAVAILABLE,
            DocumentQuotationCheck.verify(f.claim.copy(subject = "Different"), f.record, f.text, f.quotation))
        val duplicated = fixture(duplicate = true)
        assertEquals(DocumentQuotationStatus.AMBIGUOUS,
            DocumentQuotationCheck.verify(duplicated.claim, duplicated.record, duplicated.text, duplicated.quotation))
    }

    @Test fun rejectsRawNumericCoercionBeforeMatching() {
        val original = fixture()
        val badValues: List<Any> = _l[4294967297L, Int.MAX_VALUE.toLong() + 1L, -1L,
            1.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY, "1"]
        for (value in badValues) {
            val changed = editProposal(original) { it["quotationBegin"] = value }
            assertEquals(DocumentQuotationStatus.MALFORMED,
                DocumentQuotationCheck.verify(changed.claim, changed.record, changed.text, changed.quotation), "begin=$value")
        }
        val endWrap = editProposal(original) { it["quotationEnd"] = 4294967299L }
        assertEquals(DocumentQuotationStatus.MALFORMED,
            DocumentQuotationCheck.verify(endWrap.claim, endWrap.record, endWrap.text, endWrap.quotation))
        for (version in _l[4294967297L, 1.0, 1.5, "1"]) {
            val changed = editRecord(original) { it["version"] = version }
            assertEquals(DocumentQuotationStatus.MALFORMED,
                DocumentQuotationCheck.verify(changed.claim, changed.record, changed.text, changed.quotation), "version=$version")
        }
        val missing = editProposal(original) { it["quotationBegin"] = null }
        assertEquals(DocumentQuotationStatus.UNAVAILABLE,
            DocumentQuotationCheck.verify(missing.claim, missing.record, missing.text, missing.quotation))
    }

    @Test fun rejectsUnsupportedReceiptEvenWhenItsHashIsConsistent() {
        val f = fixture()
        val changedBytes = f.quotation + _a[0.toByte()]
        val changedCid = ContentId.of(changedBytes)
        val changed = editProposal(f) { it["quotationReceiptCid"] = changedCid.value }
        assertEquals(DocumentQuotationStatus.RECEIPT_MISMATCH,
            DocumentQuotationCheck.verify(changed.claim.copy(quotationReceiptCid = changedCid),
                changed.record, changed.text, changedBytes))
    }

    @Test fun rejectsMalformedOrResourceExhaustingCborAndInvalidUtf8() {
        val f = fixture()
        // CBOR map with duplicate text keys, which must not be silently collapsed.
        val duplicateKeys = _a[0xa2.toByte(), 0x61.toByte(), 0x61.toByte(), 1.toByte(), 0x61.toByte(), 0x61.toByte(), 2.toByte()]
        assertEquals(DocumentQuotationStatus.MALFORMED,
            DocumentQuotationCheck.verify(f.claim.copy(curationCid = ContentId.of(duplicateKeys)), duplicateKeys, f.text, f.quotation))
        // Map {"a": [[...]]}, too deeply nested for the bounded decoder.
        val deep = _a[0xa1.toByte(), 0x61.toByte(), 0x61.toByte()] + ByteArray(70) { 0x81.toByte() } + _a[0.toByte()]
        assertEquals(DocumentQuotationStatus.LIMIT,
            DocumentQuotationCheck.verify(f.claim.copy(curationCid = ContentId.of(deep)), deep, f.text, f.quotation))
        val invalidUtf8 = _a[0xff.toByte()]
        assertEquals(DocumentQuotationStatus.MALFORMED,
            DocumentQuotationCheck.verify(f.claim.copy(extractedTextCid = ContentId.of(invalidUtf8)), f.record, invalidUtf8, f.quotation))
        val oversized = ByteArray(16 * 1024 * 1024 + 1)
        assertEquals(DocumentQuotationStatus.LIMIT,
            DocumentQuotationCheck.verify(f.claim, oversized, f.text, f.quotation))
    }
}
