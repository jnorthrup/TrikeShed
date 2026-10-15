package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.daemon.OroborosDaemon
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.graal.subvm.harness.DocumentModelFixture
import borg.trikeshed.job.*
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.*
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LegalCurationCouncilTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var directory: Path

    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    @Test fun councilRetainsCanonicalCurationOfTheFullRootDocument(): Unit = runBlocking {
        val root = directory.toFile()
        val requests = AtomicInteger()
        val parses = AtomicInteger()
        val quote = "Acme pays Beta."
        val requirement = "Beta must retain records."
        // A page gap puts substantive source beyond the former 8,000-character extraction limit.
        val document = "$quote\n\n" + " ".repeat(8_100) +
            "\n\n$requirement\nDelays cause cancellations.\nThe clerk records the final appendix."
        val invented = "The court grants invented damages."
        val documentCid = ContentId.of(document.encodeToByteArray())
        val session = DocumentModelFixture.open { prompt ->
            requests.incrementAndGet()
            val payload = reifyMap(prompt.messages[1].content)
            val source = assertIs<Map<*, *>>(payload["source"])
            assertEquals(document, source["text"])
            assertEquals(documentCid.value, source["originalCid"])
            assertEquals(documentCid.value, source["extractedTextCid"])
            val sentences = assertIs<List<*>>(assertIs<Map<*, *>>(payload["nlp"])["sentences"])
            assertTrue(sentences.isNotEmpty(), "The model must consume actual parser rows")
            fun proposal(subject: String, predicate: String, obj: String, text: String,
                         begin: Int, modality: String) = mapOf(
                "subject" to subject, "predicate" to predicate, "object" to obj, "confidence" to 0.73,
                "quote" to text, "begin" to begin, "end" to begin + text.length,
                "polarity" to true, "modality" to modality)
            ModelResponse(jsonOf(mapOf("format" to "TRIPLET_JSON", "triplets" to listOf(
                proposal("Acme", "pay", "Beta", quote, 0, "asserted"),
                proposal("Beta", "retain", "records", requirement, document.indexOf(requirement), "required"),
                proposal("court", "grant", "damages", invented, document.length, "asserted"),
            ))), ModelUsage(3, 2, 5), "fixture", "fixture")
        }
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner + session.htx + session.reactor)
        lateinit var retained: Map<ContentId, ByteArray>
        try {
            PackCasStore(JvmFileOperations(), File(root, "cas").absolutePath).use { cas ->
                val bag = BeliefBagElement(cas = cas, parentJob = owner)
                val nlp = CoreNlpRuntime()
                val log = DocumentAppendLog.open(File(root, "curation.wal").absolutePath, root.absolutePath)
                var curator: DocumentCuratorElement? = null
                var cases: CouncilCaseRegistry? = null
                try {
                    bag.open()
                    val reader = DocumentCuratorElement.create(scope, NlpReader { text ->
                        parses.incrementAndGet()
                        assertEquals(document, text)
                        nlp.read(text)
                    }, session.model, "fixture", cas, log, bag)
                    curator = reader
                    val canonical = DocumentCurationLegos.curate(reader, DocumentCurationLegos::reference)
                    var delegations = 0
                    val curate = LcncNodeRunner { node, inputs ->
                        delegations++
                        assertEquals("ingest.curate", node.id)
                        assertEquals(DocumentCurationLegos.CURATE, node.type)
                        val source = DocumentCurationLegos.retainSource(cas, inputs["source"])
                        assertEquals(document, source.text)
                        assertEquals("legal/ingest", source.name)
                        assertEquals(source.name, source.correlation)
                        canonical.run(node, inputs + ("source" to DocumentCuratorCodec.source(source)))
                    }
                    val kif = KifKnowledgeBase()
                    val legacyKif = mutableListOf<String>()
                    val seats = mutableListOf<SeatCall>()
                    val evidenceAtSeats = mutableListOf<String>()
                    val council = InMemoryRecordStore()
                    val lifecycle = OroborosDaemon.councilCaseRegistry(scope, cas,
                        Couch("council", CouchStoreFactory.casBacked(cas), cas))
                    cases = lifecycle
                    val seams = RecordSeams.inMemory(council).copy(
                        casPut = { cas.put(it).value }, casGet = { cas.get(ContentId(it)) },
                        recordRuling = { caseId, verdictCid, transcriptCid ->
                            council.rulings.add(Triple(caseId, verdictCid, transcriptCid))
                            lifecycle.recordRuling(caseId, verdictCid, transcriptCid)
                        },
                        recordMistrial = { caseId, reason ->
                            council.mistrials.add(caseId to reason)
                            lifecycle.recordMistrial(caseId, reason)
                        },
                        kifSink = { council.kif.add(it); kif.assertKif(it) })
                    val registry = CouncilNodes.registry(CouncilDialog { call ->
                        seats.add(call)
                        evidenceAtSeats.add(kif.toKifFile())
                        val answer = if (call.role == "ruling")
                            """{"disposition":"remanded","needsClarification":false,"clarificationQuestion":"","mistrial":false}"""
                        else "Review the source attribution and the unresolved proposal before deciding."
                        SeatOutcome.Ok(answer, "deterministic-seat")
                    }, seams) + mapOf(
                        "legal.ingest" to LegalNodes.ingestRunner({ curate }, cas),
                        "legal.evidence" to LegalNodes.evidenceRunner(kif))
                    val program = CouncilProgram.build(CouncilConfig(caseId = "curation-case",
                        panels = listOf(PanelSpec("evidence", "source attribution", listOf("evidence reviewer"))),
                        rounds = 1, clarify = false, mistrial = false, roster = listOf("deterministic-seat")))
                    assertEquals(CouncilProgram.DEFAULT_MATTER,
                        program.nodes.view.single { it.id == "ingest" }.params["text"])
                    var citationReads = 0
                    val result = withContext(LegalCitationsKey { text ->
                        citationReads++
                        assertEquals(document, text)
                        emptyList()
                    } + KifSinkKey { legacyKif.add(it); kif.assertKif(it) }) {
                        LcncRunner(registry).apply { ledger = LcncConsumedLedger() }
                            .runProcedure(program, mapOf("document" to document))
                    }
                    assertEquals(1, delegations)
                    assertEquals(1, requests.get())
                    assertEquals(1, parses.get())
                    assertEquals(1, citationReads)
                    val ingest = result.nodeOutputs.getValue("ingest")
                    assertEquals(documentCid.value, ingest["documentCid"])
                    assertContentEquals(document.encodeToByteArray(), cas.get(documentCid))
                    val receiptCid = ContentId(assertIs<String>(ingest["receiptCid"]))
                    assertEquals(listOf(mapOf("kind" to "document-curation", "id" to "legal/ingest", "cid" to receiptCid.value)),
                        result.consumed.filter { it["kind"] == "document-curation" })
                    val receiptBytes = assertNotNull(cas.get(receiptCid))
                    assertEquals(receiptCid, ContentId.of(receiptBytes))
                    val record = assertNotNull(reader.record(receiptCid), "The canonical owner must retain this receipt")
                    assertContentEquals(DocumentCuratorCodec.encode(record), receiptBytes)
                    assertEquals(document, record.source.text)
                    assertEquals(documentCid, record.source.originalCid)
                    assertEquals(documentCid, record.source.extractedTextCid)
                    val parsed = assertNotNull(record.nlp)
                    assertEquals(document, parsed.text)
                    val metadata = assertNotNull(parsed.metadata)
                    assertEquals("edu.stanford.nlp.pipeline.StanfordCoreNLP", metadata.processor)
                    assertEquals(CoreNlpRuntime::class.java.name, metadata.implementation)
                    assertTrue(parsed.sentences.view.any { it.begin > 8_000 })
                    assertEquals("AVAILABLE", ingest["nlpStatus"])
                    val modelReceipt = assertNotNull(record.receipt)
                    assertEquals(200, modelReceipt.httpStatus)
                    assertFalse(modelReceipt.cachedHit)
                    assertEquals(listOf(DocumentToolStage.NLP, DocumentToolStage.MODEL,
                        DocumentToolStage.GROUNDING, DocumentToolStage.AXIOMATICS),
                        record.toolReceipts.view.map { it.stage })
                    assertTrue(record.toolReceipts.view.all { it.status == DocumentToolStatus.SUCCESS })
                    assertTrue(DocumentCurationFacts.ruleCandidates(record).size > 0)
                    for ((key, value) in DocumentCurationLegos.reference(receiptCid, record))
                        assertEquals(value, ingest[key], key)

                    assertEquals(3, record.proposals.size)
                    val admitted = record.proposals[0]
                    assertEquals(quote, admitted.quote)
                    assertEquals(0, admitted.reasons.size)
                    assertTrue(admitted.receiptCid in record.submittedReceiptCids.view)
                    val attribution = CanonicalCbor.decodeMap(assertNotNull(cas.get(assertNotNull(admitted.receiptCid))))
                    assertTrue(assertIs<String>(attribution["expression"]).startsWith("(states ${documentCid.value} "))
                    val required = record.proposals[1]
                    assertEquals(requirement, required.quote)
                    assertEquals("required", required.modality)
                    assertTrue("unsupported polarity or modality" in required.reasons.view)
                    assertTrue(required.quotationReceiptCid in record.quotationSubmittedReceiptCids.view)
                    assertFalse(required.receiptCid in record.submittedReceiptCids.view)
                    val refused = record.proposals[2]
                    assertEquals(invented, refused.quote)
                    assertTrue("quote does not match UTF-16 span" in refused.reasons.view)
                    assertNull(refused.quotationReceiptCid)
                    assertFalse(refused.receiptCid in record.submittedReceiptCids.view)
                    val elements = assertIs<Map<*, *>>(ingest["elements"])
                    assertEquals(record.proposals.view.map(DocumentCuratorCodec::proposal), elements["proposals"])
                    assertEquals(record.proposals.view.filter { it.reasons.size > 0 }.map(DocumentCuratorCodec::proposal),
                        elements["refused"])
                    assertEquals(record.reasons.view.toList(), elements["reasons"])
                    assertFalse("holdings" in elements || "parties" in elements)
                    assertTrue(legacyKif.isEmpty(), "Ingest must never assert legal holdings or parties")
                    assertTrue(evidenceAtSeats.all { it.isBlank() }, "Only council.record may add a ruling")

                    val brief = assertIs<String>(ingest["brief"])
                    assertEquals(brief, result.nodeOutputs.getValue("evidence")["brief"])
                    assertTrue(document in brief, "The brief must contain the entire source, including its tail")
                    assertTrue("source attribution only" in brief)
                    assertTrue("not adjudicated holdings or party roles" in brief)
                    assertTrue("not admitted rules" in brief)
                    assertTrue("proposals and refusals" in brief)
                    assertTrue(receiptCid.value in brief)
                    assertEquals(listOf("expert", "synthesis", "ruling"), seats.map { it.role })
                    for (seat in seats) {
                        assertTrue(brief in seat.prompt, "${seat.role} lost source/proposal/refusal distinctions")
                        assertFalse(CouncilProgram.DEFAULT_MATTER in seat.prompt)
                        for (proposal in record.proposals.view) for (reason in proposal.reasons.view)
                            assertTrue(reason in seat.prompt, "${seat.role} lost a canonical refusal")
                    }
                    val ruling = assertIs<Map<*, *>>(result.returns["ruling"])
                    assertEquals("ruled", ruling["status"])
                    assertEquals(3, ruling["seatCount"])
                    assertEquals(0, ruling["seatFailures"])
                    assertEquals(0, ruling["cidMismatches"])
                    val lifecycleCid = ContentId(assertIs<String>(ruling["recorded"]))
                    val lifecycleBytes = assertNotNull(cas.get(lifecycleCid))
                    assertEquals(lifecycleCid, ContentId.of(lifecycleBytes))
                    val snapshot = CanonicalCbor.decodeJobSnapshot(lifecycleBytes)
                    assertEquals(JobId.of("case/curation-case"), snapshot.jobId)
                    assertEquals("closed", snapshot.lifecycle)
                    assertEquals(3L, snapshot.revision)
                    assertEquals(lifecycle.recordedCid("curation-case"), lifecycleCid.value)
                    assertEquals(listOf(Triple("curation-case", ruling["verdictCid"], ruling["transcriptCid"])), council.rulings)
                    assertTrue(council.mistrials.isEmpty())
                    assertEquals(1, council.kif.size)
                    assertTrue(council.kif.single().startsWith("(ruling case_curation-case doc_${documentCid.hex} "))
                    val transcript = assertNotNull(cas.get(ContentId(ruling["transcriptCid"] as String))).decodeToString()
                    assertTrue(document in transcript)
                    assertTrue(receiptCid.value in transcript)

                    // A retained receipt for a different source, or bytes substituted beneath its CID,
                    // must refuse before optional citations; neither path re-enters extraction.
                    val receiptRunner = LcncNodeRunner { _, _ -> mapOf("receiptCid" to receiptCid.value) }
                    withContext(LegalCitationsKey { error("Unverified receipt reached citation extraction") }) {
                        val mismatch = assertFailsWith<IllegalStateException> {
                            LegalNodes.ingestRunner({ receiptRunner }, cas).run(LcncNode("mismatch", "legal.ingest"),
                                mapOf("text" to "A different filing."))
                        }
                        assertTrue("different source" in mismatch.message.orEmpty())
                        val substitute = DocumentCuratorCodec.encode(record.copy(modelId = "substituted"))
                        val substitutedCas = object : CasStore() {
                            override fun get(cid: ContentId): ByteArray? = if (cid == receiptCid) substitute else cas.get(cid)
                        }
                        val digest = assertFailsWith<IllegalStateException> {
                            LegalNodes.ingestRunner({ receiptRunner }, substitutedCas).run(LcncNode("digest", "legal.ingest"),
                                mapOf("text" to document))
                        }
                        assertTrue("digest mismatch" in digest.message.orEmpty())
                        val unavailableCid = cas.put(DocumentCuratorCodec.encode(record.copy(nlp = null)))
                        val unavailable = LcncNodeRunner { _, _ -> mapOf("receiptCid" to unavailableCid.value) }
                        val failure = assertFailsWith<IllegalStateException> {
                            LegalNodes.ingestRunner({ unavailable }, cas).run(LcncNode("unavailable", "legal.ingest"),
                                mapOf("text" to document))
                        }
                        assertTrue("validated CoreNLP" in failure.message.orEmpty())
                    }
                    retained = (listOf(documentCid, receiptCid, lifecycleCid) +
                        record.toolReceipts.view.map { assertNotNull(it.receiptCid) }).associateWith { assertNotNull(cas.get(it)) }
                    cas.sync()
                } finally {
                    cases?.close()
                    try { curator?.drain() } finally {
                        try { bag.drain() } finally { nlp.close(); log.close() }
                    }
                }
            }
            PackCasStore(JvmFileOperations(), File(root, "cas").absolutePath).use { cas ->
                for ((cid, bytes) in retained) assertContentEquals(bytes, cas.get(cid), cid.value)
            }
            assertEquals(1, requests.get(), "Receipt checks and read-back must not run another model request")
            assertEquals(1, parses.get(), "Receipt checks and read-back must not re-enter NLP")
        } finally {
            try { owner.cancelAndJoin() } finally { session.close() }
        }
    }

    @Test fun missingCanonicalOwnerRefusesWithoutExtractionOrAssertions(): Unit = runBlocking {
        var writes = 0
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId {
                writes++
                return super.put(bytes)
            }
        }
        val brain = object : BrainClient() {
            override suspend fun chat(messages: List<Pair<String, String>>, maxTokens: Int,
                                      temperature: Double, contextId: String?): String = error("Legacy extraction was invoked")
        }
        val failure = withContext(BrainClientKey(brain) +
            LegalCitationsKey { error("Missing owner reached citations") } +
            KifSinkKey { error("Missing owner asserted KIF") }) {
            assertFailsWith<IllegalStateException> {
                LegalNodes.ingestRunner({ null }, cas).run(LcncNode("missing", "legal.ingest"),
                    mapOf("text" to "Acme pays Beta."))
            }
        }
        assertTrue("canonical document.curate owner" in failure.message.orEmpty())
        assertEquals(0, writes)
    }
}
