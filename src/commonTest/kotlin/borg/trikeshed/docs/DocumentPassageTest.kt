package borg.trikeshed.docs

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.*

class DocumentPassageTest {
    private val text = "First <clause> 😀. Between. Second <clause> 😀."
    private val quote = "<clause> 😀"
    private val begin = text.lastIndexOf(quote)
    private val receipt = "sha256:" + "a".repeat(64)
    private val original = ContentId.of("original pdf".encodeToByteArray()).value
    private val extracted = ContentId.of(text.encodeToByteArray()).value
    private val source = DocRevision("matter", "agreement.pdf", original, "1-source", 1)
    private val extraction = DocRevision("matter", "agreement.pdf.extract.md", extracted, "2-text", 2)

    private fun revision(r: DocRevision) = _m["project" j r.project, "id" j r.id, "cid" j r.cid, "rev" j r.rev, "seq" j r.seq]
    private fun record(start: Any? = begin, end: Any? = begin + quote.length, quotation: String = quote,
        content: String = text, textCid: String = extracted): CurationResult = assertNotNull(DocumentSurface.curationResult(_m[
        "receiptCid" j receipt, "record" j _m[
            "source" j _m["name" j "Agreement", "text" j content, "originalCid" j original,
                "extractedTextCid" j textCid, "project" j revision(source), "extraction" j revision(extraction.copy(cid = textCid))],
            "proposals" j _l[null, _m["subject" j "Party", "predicate" j "must", "object" j "act",
                "quote" j quotation, "quotationBegin" j start, "quotationEnd" j end]],
        ],
    ]))

    private fun view(content: String = text) = DocView("matter", "agreement.pdf", extracted, "2-text", 2,
        "application/pdf", content, extract = content, hasRevisionDetails = true,
        sourceRevision = source, displayTextRevision = extraction, extractionRevision = extraction)

    @Test fun exactQuotationCarriesReceiptAndOriginalOrdinal() {
        val result = record()
        val selected = assertNotNull(DocumentSurface.passage(result, 1))
        assertNull(DocumentSurface.passage(result, 0), "ordinal refers to the original receipt, not a filtered list")
        val html = DocumentSurface.passageHtml(view(), selected)
        assertContains(html, "Between. Second <mark>&lt;clause&gt; 😀</mark>")
        assertFalse(html.contains("<clause>"))
        val attempt = DocumentSurface.curationResultHtml(result)
        assertContains(attempt, "passageProposal=1")
        assertContains(attempt, "data-passage-receipt=\"$receipt\"")
    }

    @Test fun changedSourceOrExtractionAndTruncatedPreviewFailClosed() {
        val selected = assertNotNull(DocumentSurface.passage(record(), 1))
        fun refused(doc: DocView) = assertFalse(DocumentSurface.passageHtml(doc, selected).contains("<mark>"))
        refused(view().copy(sourceRevision = source.copy(cid = ContentId.of("changed".encodeToByteArray()).value)))
        refused(view().copy(displayTextRevision = extraction.copy(rev = "3-changed"), extractionRevision = null))
        refused(view(text.take(begin + 1)))
        refused(view().copy(text = text.replaceRange(begin, begin + 1, "X")))
        refused(view().copy(text = text.replaceRange(begin - 1, begin, "!")))
        refused(view().copy(project = "other"))
        refused(view().copy(hasRevisionDetails = false, sourceRevision = null))
        assertFalse(DocumentSurface.passageHtml(view(), null).contains("<mark>"))
    }

    @Test fun malformedOffsetsAndUnverifiedTextNeverCreatePassageLinks() {
        val emoji = text.indexOf("😀")
        for (bad in _l[record(start = 1.5), record(start = -1), record(start = Long.MAX_VALUE),
            record(start = emoji + 1, end = emoji + 2, quotation = text.substring(emoji + 1, emoji + 2)),
            record(quotation = "not present"), record(content = text + "changed"),
            record().copy(receiptCid = "bad"), record().copy(sourceRevision = null),
            record().copy(extractionRevision = null)]) {
            assertNull(DocumentSurface.passage(bad, 1))
            assertFalse(DocumentSurface.curationResultHtml(bad).contains("data-passage-receipt"))
        }
        val lone = "\uD800"
        val replacementCid = ContentId.of("\uFFFD".encodeToByteArray()).value
        assertNull(DocumentSurface.passage(record(start = 0, end = 1, quotation = lone, content = lone, textCid = replacementCid), 1),
            "ill-formed UTF-16 must not hash as a replacement character")
    }
}
