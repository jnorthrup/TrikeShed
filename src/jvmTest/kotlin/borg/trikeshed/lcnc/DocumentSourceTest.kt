package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DocumentSourceTest {
    @Test
    fun binaryAndExtractionKeepSeparateIdentitiesAndStaleTwinsCannotEnter(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val corpus = InMemoryProjectCorpus()
        val bytes = "%PDF-1.7\nretained original".encodeToByteArray()
        val original = corpus.put("pilot", "report.pdf", bytes)
        val text = "The clinic reports quarterly outcomes."
        val extracted = corpus.put("pilot", "report.pdf.extract.md", text.encodeToByteArray())
        corpus.put("pilot", "report.pdf.notes.md", "url: https://publisher.example/final.pdf\nrequested: https://publisher.example/report\n".encodeToByteArray())
        val doc = corpus.document("pilot", "report.pdf")!!
        val source = DocumentCurationLegos.projectSource(cas, corpus, doc)
        assertEquals(original, source.originalCid.value)
        assertEquals(extracted, source.extractedTextCid.value)
        assertNotEquals(source.originalCid, source.extractedTextCid)
        assertContentEquals(bytes, cas.get(source.originalCid))
        assertEquals("https://publisher.example/report", source.provenance["requestedUrl"])
        assertTrue(DocumentCurationLegos.current(corpus, source))
        // Same bytes, new revision: content addressing alone must not erase the change.
        corpus.put("pilot", "report.pdf", bytes)
        assertFalse(DocumentCurationLegos.current(corpus, source))
        assertFailsWith<IllegalArgumentException> { DocumentCurationLegos.projectSource(cas, corpus, doc) }
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.projectSource(cas, corpus, corpus.document("pilot", "report.pdf")!!)
        }
    }

    @Test
    fun textCaseworkerAndAiRetainDifferentAttributionWithoutAuthenticatingPayloads() {
        val cas = CasStore.inMemory()
        val text = "This outcome needs expert review."
        val textCid = cas.put(text.encodeToByteArray())
        val run = cas.put("retained generating run".encodeToByteArray())
        val model = cas.put("retained model receipt".encodeToByteArray())
        fun source(category: String, provenance: Map<String, String> = emptyMap()) =
            DocumentCurationLegos.retainSource(cas, mapOf("name" to "assertion", "text" to text,
                "category" to category, "provenance" to provenance))
        assertEquals("text", source("text").category)
        val worker = source("caseworker", mapOf("authorId" to "worker-7", "assertionCid" to textCid.value))
        val ai = source("ai", mapOf("modelId" to "model-7", "generatingRunCid" to run.value, "modelReceiptCid" to model.value))
        assertEquals("unverified", worker.quotation(0, text.length)["authentication"])
        assertNotEquals(worker.category, ai.category)
        for (held in listOf(worker, ai)) {
            val record = DocumentCurationRecord(held, null, null, "none", emptySeriesOf(), emptySeriesOf())
            assertEquals(held, DocumentCuratorCodec.decode(DocumentCuratorCodec.encode(record)).source)
        }
        assertFailsWith<IllegalArgumentException> { source("ai", mapOf("modelId" to "missing")) }
        assertFailsWith<IllegalArgumentException> { source("caseworker", mapOf("authorId" to "worker-7")) }
        assertFailsWith<IllegalArgumentException> { source("text", mapOf("authenticated" to "true")) }
        assertFailsWith<IllegalArgumentException> { source("ai", ai.provenance + ("authorId" to "worker-7")) }
    }

    @Test
    fun manualCorrectionRetainsOriginalRevisionWithoutClaimingPdfQuotationOrLayout(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val corpus = InMemoryProjectCorpus()
        val originalBytes = "%PDF-1.7\nretained original".encodeToByteArray()
        corpus.put("pilot", "report.pdf", originalBytes)
        corpus.put("pilot", "report.pdf.extract.md", "Clinic reports outcornes.".encodeToByteArray())
        val original = DocumentCurationLegos.projectSource(cas, corpus, corpus.document("pilot", "report.pdf")!!)
        val correction = "Clinic reports outcomes."
        val corrected = original.copy(text = correction, extractedTextCid = cas.put(correction.encodeToByteArray()),
            extraction = null, anchors = emptyList(),
            unresolved = listOf("page", "folio", "section", "table", "diagram", "coordinates"),
            provenance = original.provenance + mapOf("correctionKind" to "manual-text", "correctedFrom" to original.extractedTextCid.value))
        val retained = DocumentCurationLegos.retainSource(cas, corrected)
        assertTrue(DocumentCurationLegos.current(corpus, retained))
        assertEquals(original.originalCid, retained.originalCid)
        assertEquals(original.extractedTextCid.value, retained.provenance["correctedFrom"])
        val quote = retained.quotation(0, correction.length)
        assertEquals(correction, quote["quote"])
        assertEquals("manual-correction", quote["quotationSource"])
        assertEquals(emptyList<Any?>(), quote["anchors"])
        assertContentEquals(originalBytes, cas.get(retained.originalCid))
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.retainSource(cas, corrected.copy(anchors = listOf(DocumentAnchor(0, 6, page = 1))))
        }
        assertFalse(DocumentCurationLegos.current(corpus, corrected.copy(provenance = mapOf("correctedFrom" to original.extractedTextCid.value))))
        corpus.put("pilot", "report.pdf", originalBytes)
        assertFalse(DocumentCurationLegos.current(corpus, corrected))
    }

    @Test
    fun pageLocationsPointIntoTheExactRetainedTextAndUnresolvedStructureStaysExplicit() {
        val first = PageStreams.Page(3, "ii", "7", null,
            listOf(PageStreams.Line(1, 1, 100, 40, 340, 20, "Clinic outcomes improve.")), emptyList())
        val second = PageStreams.Page(4, "3", null, null,
            listOf(PageStreams.Line(1, 1, 150, 45, 345, 20, "Staff record indicators.")), emptyList())
        val book = PageStreams.book(listOf(first, second))
        val cid = ContentId.of(book.body.encodeToByteArray())
        val source = DocumentSource(cid, cid, book.body, "pages", "text/plain", "pages", anchors = book.anchors,
            unresolved = listOf("table", "diagram"))
        val secondAnchor = source.anchors[1]
        val quotation = source.quotation(secondAnchor.begin, secondAnchor.end)
        assertEquals("Staff record indicators.", quotation["quote"])
        assertEquals(4, secondAnchor.page)
        assertEquals("3", secondAnchor.folio)
        assertEquals(listOf(45, 150, 300, 20), secondAnchor.coordinates)
        assertTrue("table" in secondAnchor.unresolved && "diagram" in secondAnchor.unresolved)
        assertNull(secondAnchor.table)
        assertFailsWith<IllegalArgumentException> { source.quotation(-1, 1) }
    }

    @Test
    fun retainedSheetNeedsNoModelOrBagOwner(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val source = DocumentCurationLegos.retainSource(cas, mapOf("name" to "text", "text" to "Retained text."))
        val record = DocumentCurationRecord(source, null, null, "none", emptySeriesOf(), emptySeriesOf())
        val cid = cas.put(DocumentCuratorCodec.encode(record))
        val result = DocumentCurationLegos.sheet(cas).run(LcncNode("sheet", "document.sheet"), mapOf("receiptCid" to cid.value))
        assertEquals(cid.value, result["receiptCid"])
        assertEquals(0, result["rowCount"])
    }
}
