package borg.trikeshed.lcnc

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.graal.subvm.harness.DocumentModelFixture
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
import borg.trikeshed.narsese.DocumentProposal
import borg.trikeshed.lib.s_
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.parse.ValueBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentCurationLegosTest {
    @Test
    fun retainedRulePagesPreserveCandidateIdentityPolarityAndSelection() {
        val text = "x"
        val textCid = ContentId.of(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "retained-rules", "text/plain", "rules")
        val nlp = NlpDocument(text, s_[NlpSentence(0, 0, 1,
            s_[NlpToken(1, 0, 1, text, text, "NN", "O")], s_[NlpDependency(0, 1, "root")])])
        // Persisted candidate rows: this verifies projection, not parser recognition.
        val axioms = listOf(900L to 0L, 0L to 900L, 0L to 700L).map { (positive, negative) ->
            val rule = borg.trikeshed.narsese.EternalRule("Delays", "cancellations",
                borg.trikeshed.narsese.NalCopula.IMPLICATION,
                borg.trikeshed.narsese.EvidenceCoord(positive, negative), textCid.value)
            borg.trikeshed.narsese.NlpcoreAxiom(0, 0, 1, "Delays", "cause", "cancellations", 0.9f, rule)
        }.toSeries()
        val record = DocumentCurationRecord(source, nlp, null, "fixture", emptySeriesOf(), emptySeriesOf(), axioms = axioms)
        val bytes = DocumentCuratorCodec.encode(record)
        val cid = ContentId.of(bytes)
        val candidates = borg.trikeshed.narsese.DocumentCurationFacts.ruleCandidates(record)
        val page = DocumentCurationLegos.sheet(cid, record, "rules", 0, 0, 1,
            s_[s_["positiveEvidence"]], s_[0 j 1])
        assertEquals(cid.value, page["receiptCid"])
        assertEquals(3, page["rowCount"])
        assertEquals(listOf(candidates[1]), page["ruleCandidates"])
        val sheet = page["sheet"] as Map<*, *>
        assertEquals("document/${cid.value}/rules", sheet["id"])
        assertEquals(2, sheet["selectedRows"])
        assertEquals(listOf(1), sheet["sourceOrdinals"])
        assertTrue((sheet["notice"] as String).contains("not admitted"))
        val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery((sheet["nextHref"] as String).substringAfter('?'))
        val next = DocumentCurationLegos.sheet(cid, record, query)
        assertEquals(listOf(candidates[2]), next["ruleCandidates"])
        assertNull(next["next"])
        assertEquals(listOf(2), (next["sheet"] as Map<*, *>)["sourceOrdinals"])
        val exhausted = DocumentCurationLegos.sheet(cid, record, "rules", 0, Int.MAX_VALUE, 32)
        assertEquals(emptyList<Any?>(), exhausted["ruleCandidates"])
        for (unavailable in listOf(record.copy(axioms = emptySeriesOf()), record.copy(nlp = null),
                record.copy(reasons = s_["source CID mismatch"]))) {
            val empty = DocumentCurationLegos.sheet(ContentId.of(DocumentCuratorCodec.encode(unavailable)),
                unavailable, "rules", 0, 0, 32)
            assertEquals(0, empty["rowCount"])
            assertEquals(emptyList<Any?>(), empty["ruleCandidates"])
            assertEquals(sheet["columns"], (empty["sheet"] as Map<*, *>)["columns"])
        }
        assertContentEquals(bytes, DocumentCuratorCodec.encode(record))
    }

    @Test
    fun retainedTupleIndexesIntersectWithoutFlatteningOrChangingProposals() {
        for (name in listOf("synthetic-medical-review", "synthetic-legal-review")) {
            val text = "λ source"
            val sourceCid = ContentId.of(text.encodeToByteArray())
            val source = DocumentSource(sourceCid, sourceCid, text, name, "text/plain", name)
            val proposals = s_[
                DocumentProposal("raw-0", "party-a", "reports", null, 0.8, polarity = true),
                DocumentProposal("raw-1", "party-a", "disputes", "finding", null, polarity = false),
                DocumentProposal("raw-2", "party-b", "reports", "finding", 0.6, polarity = true),
                DocumentProposal("raw-3", "party-a", "reports", null, null, polarity = true),
            ]
            val record = DocumentCurationRecord(source, null, null, "fixture", proposals, emptySeriesOf())
            val before = DocumentCuratorCodec.encode(record)
            val cid = ContentId.of(before)
            val page = DocumentCurationLegos.sheet(cid, record, "proposals", 0, 0, 1,
                s_[s_["subject"], s_["predicate", "polarity"], s_["object"]], s_[0 j 0, 1 j 0, 2 j 0])
            val sheet = page["sheet"] as Map<*, *>
            assertEquals(4, page["rowCount"])
            assertEquals(2, sheet["selectedRows"])
            assertEquals(listOf(0), sheet["sourceOrdinals"])
            assertTrue((sheet["notice"] as String).contains("not verified facts"))
            val columns = (sheet["columns"] as List<*>).map { (it as Map<*, *>)["name"] }
            val row = (sheet["rows"] as List<*>)[0] as List<*>
            assertEquals(sourceCid.value, row[columns.indexOf("originalCid")])
            assertNull(row[columns.indexOf("object")])
            assertEquals(0.8, row[columns.indexOf("modelConfidence")])
            val indexes = sheet["indexes"] as List<*>
            val objectGroup = (((indexes[2] as Map<*, *>)["groups"] as List<*>)[0] as Map<*, *>)
            assertEquals(listOf(null), objectGroup["key"])
            assertEquals(2, objectGroup["count"])
            fun read(href: String): Map<*, *> = DocumentCurationLegos.sheet(cid, record,
                borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(href.substringAfter('?')))["sheet"] as Map<*, *>
            val next = read(sheet["nextHref"] as String)
            assertEquals(listOf(3), next["sourceOrdinals"])
            assertNull(next["nextHref"])
            assertNull(((next["rows"] as List<*>)[0] as List<*>)[columns.indexOf("modelConfidence")])
            assertEquals(sheet, read(next["previousHref"] as String))
            val nextGroups = read(sheet["nextGroupsHref"] as String)
            assertEquals(listOf(0), nextGroups["sourceOrdinals"])
            assertEquals(sheet["selections"], nextGroups["selections"])
            assertEquals(sheet, read(nextGroups["previousGroupsHref"] as String))
            val noRows = DocumentCurationLegos.sheet(cid, record, "proposals", 0, 0, 32,
                s_[s_["subject"], s_["polarity"]], s_[0 j 1, 1 j 1])["sheet"] as Map<*, *>
            assertEquals(0, noRows["selectedRows"])
            assertEquals(emptyList<Any?>(), noRows["rows"])
            assertEquals(sheet["columns"], noRows["columns"])
            val empty = record.copy(proposals = emptySeriesOf())
            val emptySheet = DocumentCurationLegos.sheet(ContentId.of(DocumentCuratorCodec.encode(empty)), empty,
                "proposals", 0, 0, 32, s_[s_["subject"]])["sheet"] as Map<*, *>
            assertEquals(sheet["columns"], emptySheet["columns"])
            assertEquals(0, emptySheet["sourceRows"])
            assertEquals(0, ((emptySheet["indexes"] as List<*>)[0] as Map<*, *>)["groupCount"])
            assertContentEquals(before, DocumentCuratorCodec.encode(record))
            assertFailsWith<IllegalArgumentException> {
                DocumentCurationLegos.sheet(cid, record, "proposals", 0, 0, 32, s_[s_["kind"]])
            }
            assertFailsWith<IllegalArgumentException> {
                DocumentCurationLegos.sheet(cid, record, "proposals", 0, 0, 32, s_[s_["subject", "subject"]])
            }
            for (bad in listOf("[[0,0.5]]", "[[0,-1]]", "[[1,0]]", "[[0,99]]", "[[0,0],[0,1]]")) {
                assertFailsWith<IllegalArgumentException> {
                    DocumentCurationLegos.sheet(cid, record, mapOf("cursor" to "proposals", "axes" to "[[\"subject\"]]", "selections" to bad))
                }
            }
        }
    }

    @Test
    fun childIndexesStayInTheirReceiptAndSentenceScope() {
        val sourceCid = ContentId.of("source".encodeToByteArray())
        val source = DocumentSource(sourceCid, sourceCid, "source", "hierarchy", "text/plain", "hierarchy")
        // Retained parser rows, not a parser-behavior fixture. Index 42 is not source row 1.
        val nlp = NlpDocument("source", s_[
            NlpSentence(41, 0, 3, s_[NlpToken(1, 0, 3, "one", "one", "NN", "O")], emptySeriesOf()),
            NlpSentence(42, 3, 6, s_[NlpToken(1, 3, 4, "a", "a", "NN", "O"),
                NlpToken(2, 4, 5, "b", "b", "VB", "O"), NlpToken(3, 5, 6, "c", "c", "NN", "O")], emptySeriesOf()),
        ])
        val record = DocumentCurationRecord(source, nlp, null, "fixture", emptySeriesOf(), emptySeriesOf())
        val cid = ContentId.of(DocumentCuratorCodec.encode(record))
        val root = DocumentCurationLegos.sheet(cid, record, "sentences", 0, 0, 32,
            s_[s_["sentence"]], s_[0 j 1])["sheet"] as Map<*, *>
        assertEquals(listOf(1), root["sourceOrdinals"])
        val tokenRef = ((root["rows"] as List<*>)[0] as List<*>).filterIsInstance<Map<*, *>>()
            .single { (it["sheet"] as String).endsWith("/tokens") }
        val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery((tokenRef["href"] as String).substringAfter('?'))
        val tokens = DocumentCurationLegos.sheet(cid, record, query + mapOf("axes" to "[[\"tag\"],[\"ner\"]]", "selections" to "[[0,0],[1,0]]"))["sheet"] as Map<*, *>
        assertEquals(listOf(0, 2), tokens["sourceOrdinals"])
        assertEquals(3, tokens["sourceRows"])
        assertEquals(2, tokens["selectedRows"])
        assertTrue((tokens["id"] as String).endsWith("/1/tokens"))
        assertEquals(listOf(42, 42), (tokens["rows"] as List<*>).map { (it as List<*>)[2] })
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.sheet(cid, record, "sentences", 0, 0, 32, s_[s_["tokens"]])
        }
    }

    @Test
    fun retainedDocumentReferencesKeepLargeNlpOutOfLcncReporting() {
        val text = "Kotlin ".repeat(1_000).trimEnd()
        val original = ContentId.of("original document bytes".encodeToByteArray())
        val source = DocumentSource(original, ContentId.of(text.encodeToByteArray()), text,
            "synthetic.txt", "text/plain", "large-document")
        var tokenReads = 0
        val nlp = NlpDocument(text, listOf(NlpSentence(0, 0, text.length,
            1_000 j { i -> tokenReads++; NlpToken(i + 1, i * 7, i * 7 + 6, "Kotlin", "Kotlin", "NN", "O") },
            1_000 j { i -> NlpDependency(0, i + 1, "root") })).toSeries())
        val record = DocumentCurationRecord(source, nlp, null, "fixture", emptySeriesOf(), emptySeriesOf())
        val cid = ContentId.of(DocumentCuratorCodec.encode(record))
        val retained = DocumentCurationLegos.reference(cid, record)
        val reporting = mapOf("returns" to retained, "outputs" to mapOf("curate" to retained),
            "bindings" to listOf(mapOf("value" to retained)))
        assertNull(ValueBudget().violation(reporting))
        assertTrue(jsonOf(reporting).length < 4_096)
        val ref = retained.getValue("record") as Map<*, *>
        assertEquals(cid.value, ref["cid"])
        assertEquals(original.value, ref["originalCid"])
        assertEquals(source.extractedTextCid.value, ref["extractedTextCid"])
        assertEquals("/api/documents?cid=${cid.value}&view=sheet", (retained["sheet"] as Map<*, *>)["href"])
        assertEquals(DocumentCurationLegos.sheetHref(cid), (retained["sheet"] as Map<*, *>)["pageHref"])
        val full = DocumentCurationLegos.output(cid, record)
        assertNotNull(ValueBudget().violation(full))
        assertEquals(text, ((full["record"] as Map<*, *>)["source"] as Map<*, *>)["text"])
        assertEquals(1_000, (((full["sheets"] as List<*>)[1] as Map<*, *>)["rows"] as List<*>).size)
        tokenReads = 0
        val page = DocumentCurationLegos.sheet(cid, record, "tokens", 0, 128, 32)
        assertTrue(tokenReads in 32..34, "Only the page and metadata exemplars should be read: $tokenReads")
        assertNull(ValueBudget().violation(page))
        assertEquals(1_000, page["rowCount"])
        assertEquals(160, page["next"])
        val sheet = page["sheet"] as Map<*, *>
        val rows = sheet["rows"] as List<*>
        assertEquals(32, rows.size)
        assertEquals(129, (rows[0] as List<*>)[3])
        assertEquals(original.value, (rows[0] as List<*>)[0])
        assertEquals("document/${cid.value}", sheet["parent"])
        assertEquals(128, sheet["offset"])
        assertEquals(true, sheet["truncated"])
        // Follow the actual read URLs: no guessed sheet-ID parsing or reparsing the document.
        fun read(href: String): Map<String, Any?> = DocumentCurationLegos.sheet(cid, record,
            borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(href.substringAfter('?')))
        val next = read(sheet["nextHref"] as String)
        assertEquals(160, (next["sheet"] as Map<*, *>)["offset"])
        assertEquals(sheet, read((next["sheet"] as Map<*, *>)["previousHref"] as String)["sheet"])
        val root = read(sheet["parentHref"] as String)["sheet"] as Map<*, *>
        assertEquals("document/${cid.value}", root["id"])
        assertNull(root["previousHref"])
        assertNull(root["nextHref"])
        val child = ((root["rows"] as List<*>)[0] as List<*>).filterIsInstance<Map<*, *>>()
            .single { it["sheet"] == "document/${cid.value}/0/tokens" }
        tokenReads = 0
        val opened = read(child["href"] as String)["sheet"] as Map<*, *>
        assertEquals(child["sheet"], opened["id"])
        assertEquals(0, opened["offset"])
        assertEquals(32, (opened["rows"] as List<*>).size)
        assertTrue(tokenReads in 32..34)
        val last = read(DocumentCurationLegos.sheetHref(cid, "tokens", 0, 992, 32))["sheet"] as Map<*, *>
        assertEquals(8, (last["rows"] as List<*>).size)
        assertNull(last["nextHref"])
        for (bad in listOf("-1", "1.5", "2147483648", "not-an-index")) {
            assertFailsWith<IllegalArgumentException> {
                DocumentCurationLegos.sheet(cid, record, mapOf("offset" to bad))
            }
        }
        val exhausted = DocumentCurationLegos.sheet(cid, record, "tokens", 0, Int.MAX_VALUE, 32)
        assertEquals(emptyList<Any?>(), (exhausted["sheet"] as Map<*, *>)["rows"])
        assertEquals(sheet["columns"], (exhausted["sheet"] as Map<*, *>)["columns"])
        assertNull(exhausted["next"])
        for (view in listOf("sentences", "tokens", "dependencies")) {
            assertFailsWith<IllegalArgumentException> { DocumentCurationLegos.sheet(cid, record, view, 0, 0, 129) }
            assertFailsWith<IllegalArgumentException> { DocumentCurationLegos.sheet(cid, record, view, 0, -1, 32) }
        }
        assertFailsWith<IllegalArgumentException> { DocumentCurationLegos.sheet(cid, record, "tokens", 1, 0, 32) }
        assertFailsWith<IllegalArgumentException> { DocumentCurationLegos.sheet(cid, record, "unknown", 0, 0, 32) }
    }

    @Test
    fun documentOutputProjectsNlpAxiomsAsLabeledAdmissionCandidates() {
        // This fixture exercises the JSON projection; parser behavior has separate coverage.
        val text = "Rain causes floods"
        val original = ContentId.of("axiom original bytes".encodeToByteArray())
        val extracted = ContentId.of(text.encodeToByteArray())
        val source = DocumentSource(original, extracted, text, "axiom.txt", "text/plain", "axiom-fixture")
        val tokens = listOf(
            NlpToken(1, 0, 4, "Rain", "Rain", "NN", "O"),
            NlpToken(2, 5, 11, "causes", "cause", "VBZ", "O"),
            NlpToken(3, 12, 18, "floods", "flood", "NNS", "O"),
        ).toSeries()
        val dependencies = listOf(
            NlpDependency(0, 2, "root"),
            NlpDependency(2, 1, "nsubj"),
            NlpDependency(2, 3, "obj"),
        ).toSeries()
        val nlp = NlpDocument(text, listOf(NlpSentence(0, 0, text.length, tokens, dependencies)).toSeries())
        val record = DocumentCurationRecord(source, nlp, null, "fixture", emptySeriesOf(), emptySeriesOf())
        val cid = ContentId.of(DocumentCuratorCodec.encode(record))

        val axioms = DocumentCurationLegos.output(cid, record)["nlpAxioms"] as List<*>
        assertEquals(1, axioms.size)
        val row = axioms[0] as Map<*, *>
        assertEquals(original.value, row["originalCid"])
        assertEquals(extracted.value, row["extractedTextCid"])
        assertEquals(0, row["sentenceIndex"])
        assertEquals(0, row["begin"])
        assertEquals(text.length, row["end"])
        assertEquals(text, row["quote"])
        assertEquals("Rain", row["antecedent"])
        assertEquals("cause", row["predicate"])
        assertEquals("floods", row["consequent"])
        assertEquals("admission candidate", row["label"])

        // Empty case: no retained NLP means no recomputable candidates, not a missing key.
        val unparsed = record.copy(nlp = null)
        assertEquals(emptyList<Any?>(), DocumentCurationLegos.output(cid, unparsed)["nlpAxioms"])
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
            val session = DocumentModelFixture.open(modelId = "fixture") { prompt ->
                modelCalls++
                assertEquals(instructions, prompt.messages[0].content)
                val sent = reifyMap(prompt.messages[1].content)
                assertEquals(text, (sent["source"] as Map<*, *>)["text"])
                assertNotNull(sent["nlp"])
                ModelResponse("""{"format":"TRIPLET_JSON","triplets":[{"subject":"Synthetic applicant","predicate":"skill","object":"Kotlin","confidence":0.9,"quote":"Kotlin","begin":0,"end":6,"polarity":true,"modality":"asserted"}]}""",
                    ModelUsage(0, 0, 0), "fixture", "fixture")
            }
            val curator = DocumentCuratorElement.create(
                CoroutineScope(coroutineContext + session.htx + session.reactor),
                NlpReader {
                    assertEquals(text, it)
                    NlpDocument(it, listOf(NlpSentence(0, 0, it.length,
                        listOf(NlpToken(1, 0, it.length, it, it, "NN", "O")).toSeries(),
                        listOf(NlpDependency(0, 1, "root")).toSeries())).toSeries())
                }, session.model, "fixture", cas, log, bag)
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
                val ledger = LcncConsumedLedger()
                val frameCount = frames.size
                val read = DocumentCurationLegos.sheet(curator)
                withContext(ledger) {
                    repeat(2) {
                        val page = read.run(LcncNode("read", DocumentCurationLegos.SHEET,
                            mapOf("receiptCid" to cid.value, "view" to "tokens", "axes" to "[[\"tag\"],[\"ner\"]]", "selections" to "[[0,0],[1,0]]")),
                            mapOf("receiptCid?" to cid.value))
                        assertEquals(1, page["rowCount"])
                        assertEquals(1, LcncSheetNodes.countNode().run(LcncNode("count", "sheet.count"), page)["count"])
                    }
                }
                assertEquals(cid.value, ledger.entries().single().cid)
                assertFailsWith<IllegalArgumentException> {
                    read.run(LcncNode("read", DocumentCurationLegos.SHEET,
                        mapOf("view" to "tokens", "axes" to "[[\"tag\"]]", "selections" to "[[0,0]]")),
                        mapOf("receiptCid?" to cid.value))
                }
                assertEquals(1, modelCalls)
                assertEquals(frameCount, frames.size)
                curator.drain(); bag.drain()
                assertEquals(record.quotationSubmittedReceiptCids[0].value, bag.snapshot().values.single().provenanceCid)
            } finally { curator.drain(); bag.drain(); session.close() }
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
        val extent = DocumentCurationLegos.extent(reify(
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
            LcncNode("read", DocumentCurationLegos.SHEET),
            LcncNode("count", "sheet.count"),
        ).toSeries(), listOf(
            LcncWire("source", "value", "curate", "extent"),
            LcncWire("curate", "receiptCid", "read", "receiptCid?"),
            LcncWire("read", "sheet", "count", "sheet"),
        ).toSeries())
        assertEquals(LcncNodeKey.DOCUMENT_CURATE, LcncNodeKey.of(DocumentCurationLegos.CURATE))
        assertEquals(LcncNodeKey.DOCUMENT_SHEET, LcncNodeKey.of(DocumentCurationLegos.SHEET))
        assertTrue(LcncTypeCheck.check(program).isEmpty())
    }
}
