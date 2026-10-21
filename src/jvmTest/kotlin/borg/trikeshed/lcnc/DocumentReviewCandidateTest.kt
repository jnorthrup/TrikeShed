package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import kotlin.test.*

class DocumentReviewCandidateTest {
    @Test fun selectionReadsExactRetainedProposalAndKeepsAuthorUnverified() {
        val cas = CasStore.inMemory()
        val text = "Synthetic clinic provides services."
        val textCid = cas.put(text.encodeToByteArray())
        val assertion = cas.put("synthetic caseworker assertion".encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "Synthetic evidence", "text/plain", "synthetic:case",
            category = "caseworker", provenance = mapOf("authorId" to "unverified:author", "assertionCid" to assertion.value))
        val proposal = DocumentProposal("synthetic raw response", "Synthetic clinic", "provide", "services",
            quote = text, begin = 0, end = text.length, polarity = true, modality = "asserted")
        val record = DocumentCurationRecord(source, null, null, "synthetic model", s_[proposal], emptySeriesOf(),
            toolOntology = s_["synthetic:Clinic", "synthetic:Service"])
        val receipt = cas.put(DocumentCuratorCodec.encode(record))
        val candidate = DocumentReviewCandidate.candidate(cas, receipt, listOf("proposal:0"))
        assertEquals(receipt, candidate.curationReceiptCid)
        assertEquals(textCid, candidate.selections[0].quotation.originalCid)
        assertEquals(text, candidate.selections[0].quotation.quote)
        assertEquals("services", candidate.selections[0].value["object"])
        val evidence = candidate.selections[0].value["sourceEvidence"] as Map<*, *>
        assertEquals("unverified", evidence["authentication"])
        assertEquals("unverified:author", (evidence["provenance"] as Map<*, *>)["authorId"])
        assertEquals(listOf("synthetic:Clinic", "synthetic:Service"),
            CanonicalCbor.decodeMap(cas.get(candidate.ontologyCid)!!)["entries"])
        assertFailsWith<IllegalStateException> { DocumentReviewCandidate.candidate(cas, receipt, listOf("proposal:1")) }
    }

    @Test fun missingSourceOrInventedQuotationCannotBecomeReviewableMaterial() {
        val cas = CasStore.inMemory()
        val text = "Synthetic evidence."
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "synthetic.txt", "text/plain", "synthetic")
        val invented = DocumentProposal("synthetic raw response", "Clinic", "provide", "services",
            quote = "This passage was invented.", begin = 0, end = text.length,
            quotationBegin = 0, quotationEnd = text.length)
        val record = DocumentCurationRecord(source, null, null, "synthetic model", s_[invented], emptySeriesOf())
        val receipt = cas.put(DocumentCuratorCodec.encode(record))
        assertTrue(DocumentReviewCandidate.selections(record).isEmpty())
        assertFailsWith<IllegalStateException> { DocumentReviewCandidate.candidate(cas, receipt, listOf("proposal:0")) }
        val missing = record.copy(source = source.copy(originalCid = ContentId.of("missing original".encodeToByteArray())))
        val missingReceipt = cas.put(DocumentCuratorCodec.encode(missing))
        assertFailsWith<IllegalArgumentException> { DocumentReviewCandidate.candidate(cas, missingReceipt, listOf("proposal:0")) }
    }
}
