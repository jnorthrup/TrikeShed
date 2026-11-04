package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DocumentSpansTest {
    private fun source(text: String): DocumentSource {
        val cid = ContentId.of(text.encodeToByteArray())
        return DocumentSource(cid, cid, text, "unicode.txt", "text/plain", "test")
    }

    @Test fun continuationCoversExactUnicodeAndEscapesWithoutSplittingSurrogates() {
        val source = source("é水🙂 \"quote\" \\\nsecond line\n".repeat(25))
        val manifest = ContentId.of("manifest".encodeToByteArray())
        val quotes = StringBuilder()
        var offset = 0
        while (offset < source.text.length) {
            val batch = DocumentSpans.batch(source, manifest, offset, 2, 17, 2048)
            val decoded = reifyStrict(jsonOf(batch)) as Map<*, *>
            for (row in decoded["spans"] as List<*>) {
                val quote = (row as Map<*, *>)["quotation"] as Map<*, *>
                val begin = (quote["begin"] as Number).toInt(); val end = (quote["end"] as Number).toInt()
                assertEquals(source.text.substring(begin, end), quote["quote"])
                quotes.append(quote["quote"])
            }
            val next = (batch["end"] as Number).toInt()
            assertTrue(next > offset)
            assertTrue(jsonOf(batch).encodeToByteArray().size <= 2048)
            assertEquals(next == source.text.length, batch["complete"])
            offset = next
        }
        assertEquals(source.text, quotes.toString())
        assertFails { DocumentSpans.batch(source, manifest, 3, 2, 17, 2048) }
        assertEquals(true, DocumentSpans.batch(source, manifest, offset, 2, 17, 2048)["complete"])
    }

    @Test fun oversizedPassageRefusesWithoutPretendingProgressOrClippingAnchors() {
        val source = source("x".repeat(4096)).copy(anchors = _l[DocumentAnchor(0, 4096, page = 12)])
        val manifest = ContentId.of("manifest".encodeToByteArray())
        assertFails { DocumentSpans.batch(source, manifest, 0, 1, 4096, 1024) }
        val batch = DocumentSpans.batch(source, manifest, 0, 1, 256, 2048)
        val quote = ((batch["spans"] as List<*>).single() as Map<*, *>)["quotation"] as Map<*, *>
        assertEquals(12, ((quote["anchors"] as List<*>).single() as Map<*, *>)["page"])
        assertEquals(256, batch["nextOffset"])
        assertEquals(false, batch["complete"])
    }

    @Test fun compactSourceRoundTripsAndRejectsAlteredMetadataAndBytes(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val corpus = InMemoryProjectCorpus()
        val input = _m["text" j "A retained statement. 🙂", "name" j "statement.txt"]
        val result = DocumentCurationLegos.source(cas, corpus).execute(
            LcncNode("source", "document.source", _m["view" j "reference"]), _m["source" j input])
        val reference = result["source"] as Map<String, Any?>
        assertFalse("text" in reference); assertFalse("anchors" in reference)
        assertEquals(input["text"], DocumentCurationLegos.retainSource(cas, reference).text)
        assertFails { DocumentCurationLegos.retainSource(cas, reference + _m["name" j "another.txt"]) }
        assertFails { DocumentCurationLegos.retainSource(cas, reference + _m["text" j "changed"]) }
        val forged = reference + _m["sourceCid" j ContentId.of("absent".encodeToByteArray()).value]
        assertFails { DocumentCurationLegos.retainSource(cas, forged) }
        val output = DocumentSpans.runner(cas, corpus).execute(LcncNode("spans", "document.spans"), _m["source" j reference])
        val batch = (output["data"] as String).encodeToByteArray()
        assertEquals(ContentId.of(batch).value, output["batchCid"])
        assertContentEquals(batch, cas.get(ContentId(output["batchCid"] as String)))
        assertEquals(reference["sourceCid"], output["sourceCid"])
    }

    @Test fun pausedReferenceCannotConsumeARevisedProjectDocument(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val corpus = InMemoryProjectCorpus()
        corpus.put("case", "opinion.pdf", "%PDF-test-only".encodeToByteArray())
        corpus.put("case", "opinion.pdf.extract.md", "Exact extracted body".encodeToByteArray())
        val doc = assertNotNull(corpus.document("case", "opinion.pdf"))
        val reference = DocumentCurationLegos.source(cas, corpus).execute(
            LcncNode("source", "document.source", _m["view" j "reference"]), _m["doc" j doc.toMap()])["source"]
        val runner = DocumentSpans.runner(cas, corpus)
        runner.execute(LcncNode("spans", "document.spans"), _m["source" j reference])
        corpus.put("case", "opinion.pdf", "%PDF-revised-test-only".encodeToByteArray())
        assertFails { runner.execute(LcncNode("spans", "document.spans"), _m["source" j reference]) }
    }
}
