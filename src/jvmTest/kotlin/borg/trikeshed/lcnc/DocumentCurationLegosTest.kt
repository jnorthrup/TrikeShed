package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.narsese.DocumentCurationRecord
import borg.trikeshed.narsese.DocumentCuratorElement
import borg.trikeshed.narsese.DocumentSource
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.json.ValueBudget
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentCurationLegosTest {
    @Test
    fun retainedDocumentReferencesKeepLargeNlpOutOfLcncReporting() {
        val text = "Kotlin ".repeat(1_000).trimEnd()
        val original = ContentId.of("original document bytes".encodeToByteArray())
        val source = DocumentSource(original, ContentId.of(text.encodeToByteArray()), text,
            "synthetic.txt", "text/plain", "large-document")
        val nlp = NlpDocument(text, listOf(NlpSentence(0, 0, text.length,
            1_000 j { i -> NlpToken(i + 1, i * 7, i * 7 + 6, "Kotlin", "Kotlin", "NN", "O") },
            1_000 j { i -> NlpDependency(0, i + 1, "root") })).toSeries())
        val record = DocumentCurationRecord(source, nlp, null, "fixture", emptySeriesOf(), emptySeriesOf())
        val cid = ContentId.of(DocumentCuratorCodec.encode(record))
        val retained = DocumentCurationLegos.reference(cid, record)
        val reporting = mapOf("returns" to retained, "outputs" to mapOf("curate" to retained),
            "bindings" to listOf(mapOf("value" to retained)))
        assertNull(ValueBudget().violation(reporting))
        assertTrue(JsonSupport.stringify(reporting).length < 4_096)
        val ref = retained.getValue("record") as Map<*, *>
        assertEquals(cid.value, ref["cid"])
        assertEquals(original.value, ref["originalCid"])
        assertEquals(source.extractedTextCid.value, ref["extractedTextCid"])
        assertEquals("/api/documents?cid=${cid.value}&view=sheet", (retained["sheet"] as Map<*, *>)["href"])
        val full = DocumentCurationLegos.output(cid, record)
        assertNotNull(ValueBudget().violation(full))
        assertEquals(text, ((full["record"] as Map<*, *>)["source"] as Map<*, *>)["text"])
        assertEquals(1_000, (((full["sheets"] as List<*>)[1] as Map<*, *>)["rows"] as List<*>).size)
    }

    @Test
    fun canvasOptionalSourcePortRunsReadyTextThroughSharedCurator(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = CasStore.inMemory()
            val text = "Kotlin"
            val source = DocumentSource(cas.put("original document".encodeToByteArray()),
                cas.put(text.encodeToByteArray()), text, "synthetic.txt", "text/plain", "canvas-source")
            val instructions = "  Preserve the exact prior curation recipe.\nKeep source quotations separate from interpretations.  "
            val frames = linkedMapOf<Long, ByteArray>()
            var flushed = 0L
            val log = object : DurableAppendLog {
                override fun append(sequence: Long, payload: ByteArray): Long {
                    frames[sequence] = payload.copyOf()
                    return sequence
                }
                override fun flush() { flushed = frames.keys.lastOrNull() ?: 0L }
                override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
                    for ((seq, payload) in frames) if (seq <= flushed) onFrame(seq, payload.copyOf())
                    return flushed
                }
                override fun injectCorruptionAfter(sequence: Long) = error("not used")
            }
            val bag = BeliefBagElement(capacity = 8, cas = cas)
            bag.open()
            var modelCalls = 0
            val curator = DocumentCuratorElement.create(this, NlpReader {
                assertEquals(text, it)
                NlpDocument(it, listOf(NlpSentence(0, 0, it.length,
                    listOf(NlpToken(1, 0, it.length, it, it, "NN", "O")).toSeries(),
                    listOf(NlpDependency(0, 1, "root")).toSeries())).toSeries())
            }, { prompt ->
                modelCalls++
                assertEquals(instructions, prompt.messages[0].content)
                val sent = JsonSupport.parseMap(prompt.messages[1].content)
                assertEquals(text, (sent["source"] as Map<*, *>)["text"])
                assertNotNull(sent["nlp"])
                ModelResponse("""{"format":"TRIPLET_JSON","triplets":[{"subject":"Synthetic applicant","predicate":"skill","object":"Kotlin","confidence":0.9,"quote":"Kotlin","begin":0,"end":6,"polarity":true,"modality":"asserted"}]}""",
                    ModelUsage(0, 0, 0), "fixture", "fixture")
            }, "fixture", cas, log, bag)
            try {
                val program = LcncProgram("canvas-document", listOf(
                    LcncNode("source", "scope.in", mapOf("name" to "source", "kind" to "json")),
                    LcncNode("instructions", "scope.in", mapOf("name" to "instructions", "kind" to "text")),
                    LcncNode("curate", DocumentCurationLegos.CURATE, mapOf("instructions" to "Node parameter must yield to the wired recipe")),
                    LcncNode("receipt", "scope.out", mapOf("name" to "receiptCid", "kind" to "id")),
                ).toSeries(), listOf(
                    LcncWire("source", "value", "curate", "source?"),
                    LcncWire("instructions", "value", "curate", "instructions?"),
                    LcncWire("curate", "receiptCid", "receipt", "value"),
                ).toSeries())
                assertTrue(LcncTypeCheck.check(program).isEmpty())
                val runner = DocumentCurationLegos.curate(curator, DocumentCurationLegos::reference)
                val result = LcncRunner(mapOf(DocumentCurationLegos.CURATE to runner))
                    .runProcedure(program, mapOf("source" to DocumentCuratorCodec.source(source), "instructions" to instructions))
                val cid = ContentId(result.returns["receiptCid"] as String)
                val record = assertNotNull(curator.record(cid))
                assertEquals(source, record.source)
                assertEquals(instructions, record.instructions)
                assertEquals(instructions, DocumentCuratorCodec.decode(assertNotNull(cas.get(cid))).instructions)
                for (port in listOf("instructions", "instructions?")) {
                    val rejected = assertFailsWith<IllegalArgumentException> {
                        runner.run(program.nodes[2], mapOf("source" to source, port to 42))
                    }
                    assertEquals("document.curate.instructions must be a string", rejected.message)
                }
                assertEquals(1, modelCalls)
                assertTrue(flushed > 0)
                assertEquals("AVAILABLE", result.nodeOutputs.getValue("curate")["nlpStatus"])
                assertEquals(0, record.submittedReceiptCids.size)
                assertEquals(1, record.quotationSubmittedReceiptCids.size)
                curator.drain(); bag.drain()
                assertEquals(record.quotationSubmittedReceiptCids[0].value, bag.snapshot().values.single().provenanceCid)
            } finally { curator.drain(); bag.drain() }
        }
    }

    @Test
    fun sourceDecodingPreservesTextAndBinaryBytes() {
        val text = "\u0000λ source"
        val decoded = DocumentCurationLegos.content(mapOf("name" to "source.txt", "text" to text))
        assertContentEquals(text.encodeToByteArray(), decoded.bytes)
        val binary = byteArrayOf(0, -1, 10)
        val encoded = java.util.Base64.getEncoder().encodeToString(binary)
        assertContentEquals(binary, DocumentCurationLegos.content(mapOf("name" to "source.bin", "base64" to encoded)).bytes)
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.content(mapOf("name" to "source", "text" to text, "base64" to encoded))
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.content(mapOf("name" to "source", "base64" to "not base64"))
        }
    }

    @Test
    fun jsonExtentsRetainIntegralCoordinates() {
        val extent = DocumentCurationLegos.extent(JsonSupport.parse(
            """{"lba":8,"byteLength":3,"name":"source.txt","mediaType":"text/plain"}"""))
        assertEquals(8L, extent.lba)
        assertEquals(3, extent.byteLength)
        assertEquals("source.txt", extent.name)
        assertEquals("text/plain", extent.mediaType)
    }

    @Test
    fun invalidExtentsCannotBecomeDifferentValidCoordinates() {
        val extent = mapOf("lba" to 0L, "byteLength" to 3, "name" to "source.txt")
        for (fields in listOf(
            extent + ("lba" to 0.5),
            extent + ("lba" to 9_007_199_254_740_992.0),
            extent + ("byteLength" to 2_147_483_648L),
            extent + ("byteLength" to -1),
            extent + ("name" to ""),
            extent - "name",
        )) assertFailsWith<IllegalArgumentException> { DocumentCurationLegos.extent(fields) }
    }

    @Test
    fun documentSheetMatesWithExistingSheetConsumer() {
        val program = LcncProgram("document-sheet", listOf(
            LcncNode("source", "scope.in", mapOf("name" to "extent")),
            LcncNode("curate", DocumentCurationLegos.CURATE),
            LcncNode("count", "sheet.count"),
        ).toSeries(), listOf(
            LcncWire("source", "value", "curate", "extent"),
            LcncWire("curate", "sheet", "count", "sheet"),
        ).toSeries())
        assertEquals(LcncNodeKey.DOCUMENT_CURATE, LcncNodeKey.of(DocumentCurationLegos.CURATE))
        assertTrue(LcncTypeCheck.check(program).isEmpty())
    }
}
