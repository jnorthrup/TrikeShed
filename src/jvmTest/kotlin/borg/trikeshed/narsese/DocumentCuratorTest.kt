package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.graal.subvm.harness.DocumentModelFixture
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.lcnc.DocumentCurationLegos
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lib.get
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import borg.trikeshed.modelmux.*
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DocumentCuratorTest {
    @Test fun retainedTripletEnvelopeWithoutFormatReachesBothGroundingPaths(): Unit = runBlocking {
        withTimeout(10000) {
            // Exact synthetic glm-5.3-flash content and CoreNLP annotations retained on 2026-09-14.
            // Replaying this fixture performs no network request and does not rewrite that receipt.
            val raw = """{"triplets":[{"subject":"Rain","predicate":"cause","object":"floods","confidence":0.95,"quote":"Rain causes floods.","begin":0,"end":19,"polarity":true,"modality":"asserted"}]}"""
            val text = "Rain causes floods."
            val nlp = NlpDocument(text, listOf(NlpSentence(0, 0, 19, listOf(
                NlpToken(1, 0, 4, "Rain", "rain", "NN", "O"),
                NlpToken(2, 5, 11, "causes", "cause", "VBZ", "O"),
                NlpToken(3, 12, 18, "floods", "flood", "NNS", "CAUSE_OF_DEATH"),
                NlpToken(4, 18, 19, ".", ".", ".", "O"),
            ).toSeries(), listOf(
                NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
                NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct"),
            ).toSeries())).toSeries())
            val f = fixture(this, text)
            val source = f.source.copy(originalCid = f.source.extractedTextCid,
                name = "synthetic-clause.txt", metadata = emptyMap())
            assertEquals("sha256:65a2a52ac6dea1239309d68bcf0caceeb0439869f39d46ae8befec07f4732e77", source.originalCid.value)
            val response = ModelResponse(raw, ModelUsage(748, 458, 1206), "override", "glm-5.3-flash")
            val curator = f.curator(this, nlp = NlpReader { assertEquals(text, it); nlp }, model = { response })
            try {
                val result = curator.curate(source)
                val p = result.record.proposals[0]
                assertEquals(0, p.reasons.size)
                assertEquals(0, result.unresolvedReasons.size)
                assertEquals(1, result.record.submittedReceiptCids.size)
                assertEquals(1, result.record.quotationSubmittedReceiptCids.size)
                assertEquals(19, p.end)
                assertEquals(raw, result.record.model!!.content)
                val stored = DocumentCuratorCodec.decode(assertNotNull(f.cas.get(result.recordCid)))
                assertEquals(raw, stored.model!!.content)
                assertFalse(JsonSupport.parseMap(stored.model.content).containsKey("format"))
                assertEquals(DocumentCuratorCodec.nlp(nlp), DocumentCuratorCodec.nlp(assertNotNull(stored.nlp)))

                val envelope = JsonSupport.parseMap(raw)
                val explicit = DocumentCuratorGrounding.reconcile(source, nlp,
                    DocumentCuratorGrounding.parse(JsonSupport.stringify(envelope + ("format" to "TRIPLET_JSON"))))[0]
                assertEquals(0, explicit.reasons.size)
                assertEquals(p.subject, explicit.subject)
                assertEquals(p.predicate, explicit.predicate)
                assertEquals(p.obj, explicit.obj)
                for (invalid in listOf(
                    envelope + ("format" to "KIF"), envelope + ("format" to null),
                    envelope + ("commentary" to "extra envelope fields remain unsupported"),
                )) {
                    val refused = DocumentCuratorGrounding.parse(JsonSupport.stringify(invalid))[0]
                    assertNull(refused.subject)
                    assertTrue(refused.reasons.size > 0)
                }
                curator.drain(); f.bag.drain(); f.closeSessions()
                val beliefs = f.bag.snapshot().values
                assertEquals(2, beliefs.size)
                assertTrue(beliefs.any { it.provenanceCid == p.receiptCid!!.value })
                assertTrue(beliefs.any { it.provenanceCid == p.quotationReceiptCid!!.value })
            } finally { curator.drain(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun profileQuotationReachesIntakeWithoutAdmittingInterpretationOrReinforcingOnReplay(): Unit = runBlocking {
        withTimeout(10000) {
            val quote = "Built Kotlin Channel and Flow services."
            val text = "Synthetic résumé\n$quote"
            val f = fixture(this, text)
            val begin = text.indexOf(quote)
            var raw = envelope(proposal(quote = quote, begin = begin, end = text.length, predicate = "skill") +
                mapOf("subject" to "Synthetic applicant", "object" to "Kotlin Channel and Flow services"))
            val reader = NlpReader(::documentTokens)
            val model: suspend (Prompt) -> ModelResponse = { response(raw) }
            val first = f.curator(this, nlp = reader, model = model)
            val result = try {
                val result = first.curate(f.source)
                val p = result.record.proposals[0]
                assertEquals(0, result.acceptedReceiptCids.size)
                assertTrue(p.reasons.size > 0)
                assertEquals(1, result.record.quotationSubmittedReceiptCids.size)
                val quotation = assertNotNull(p.quotationReceiptCid)
                assertEquals(quotation, result.record.quotationSubmittedReceiptCids[0])
                assertEquals(begin, p.quotationBegin)
                assertEquals(text.length, p.quotationEnd)
                assertFalse(p.receiptCid == quotation)
                val receipt = CanonicalCbor.decodeMap(assertNotNull(f.cas.get(quotation)))
                assertEquals("quotation", receipt["kind"])
                assertEquals(f.source.originalCid.value, receipt["originalCid"])
                assertEquals(f.source.extractedTextCid.value, receipt["extractedTextCid"])
                assertEquals(quote, receipt["quote"])
                assertEquals(begin, (receipt["begin"] as Number).toInt())
                assertEquals(text.length, (receipt["end"] as Number).toInt())
                val attribution = result.attributions.values().single()
                assertEquals(quotation, attribution.receiptCid)
                assertEquals("quotes", (attribution.expression as KifExpr.ListExpr).elements[0].toKifString())
                assertFalse(attribution.expression.toKifString().contains("Synthetic applicant"))
                assertEquals(NalCopula.PRODUCT, attribution.mapped.copula)

                // The quotation identity survives a different interpretation and wrong model offsets.
                raw = envelope(proposal(quote = quote, begin = 0, end = quote.length, predicate = "experience") +
                    mapOf("subject" to "Synthetic applicant", "object" to "Concurrent Kotlin services"))
                val repeated = first.curate(f.source.copy(correlation = "repeat"))
                val resolved = repeated.record.proposals[0]
                assertEquals(0, resolved.begin)
                assertEquals(quote.length, resolved.end)
                assertEquals(begin, resolved.quotationBegin)
                assertEquals(text.length, resolved.quotationEnd)
                assertEquals(quotation, resolved.quotationReceiptCid)
                assertTrue(resolved.reasons.values().any { "quote does not match" in it })
                assertEquals(0, repeated.record.quotationSubmittedReceiptCids.size)
                assertEquals(quotation, repeated.record.quotationDuplicateReceiptCids[0])
                result
            } finally { first.drain() }
            val restored = f.curator(this, nlp = reader, model = model)
            try {
                val stored = assertNotNull(restored.record(result.recordCid)).proposals[0]
                assertEquals(result.record.proposals[0].quotationReceiptCid, stored.quotationReceiptCid)
                val repeated = restored.curate(f.source)
                assertEquals(0, repeated.acceptedReceiptCids.size)
                assertEquals(0, repeated.record.quotationSubmittedReceiptCids.size)
                assertEquals(stored.quotationReceiptCid, repeated.record.quotationDuplicateReceiptCids[0])
                restored.drain(); f.bag.drain(); f.closeSessions()
                val belief = f.bag.snapshot().values.single()
                assertEquals(stored.quotationReceiptCid!!.value, belief.provenanceCid)
                assertEquals(Nal.UNIT, belief.evidence.positive)
                assertEquals(0L, belief.evidence.negative)
                assertTrue(f.bag.glossOf(belief.angular)!!.startsWith("(quotes "))
            } finally { restored.drain(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun missingAndAmbiguousQuotationsRemainPendingWithoutIntake(): Unit = runBlocking {
        withTimeout(10000) {
            val quote = "Kotlin Channel and Flow"
            val text = "Skills: $quote\nPrevious: $quote"
            val f = fixture(this, text)
            var raw = envelope(proposal(quote = quote, begin = 0, end = quote.length, predicate = "skill"))
            val curator = f.curator(this, nlp = NlpReader(::documentTokens), model = { response(raw) })
            try {
                for (selected in listOf(quote, "Rust async services")) {
                    raw = envelope(proposal(quote = selected, begin = 0, end = selected.length, predicate = "skill"))
                    val result = curator.curate(f.source.copy(correlation = "quotation-$selected"))
                    val p = result.record.proposals[0]
                    assertNull(p.quotationReceiptCid)
                    assertNull(p.quotationBegin)
                    assertNull(p.quotationEnd)
                    assertEquals(0, result.record.quotationReservedReceiptCids.size)
                    assertEquals(0, result.record.quotationSubmittedReceiptCids.size)
                    assertEquals(0, result.acceptedReceiptCids.size)
                    assertTrue(p.reasons.size > 0)
                }
                curator.drain(); f.bag.drain(); f.closeSessions()
                assertEquals(0, f.bag.size)
            } finally { curator.drain(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun quotationReservationSuppressesUncertainDeliveryAfterReplay(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            f.log.failFlushAt = 2
            val model: suspend (Prompt) -> ModelResponse = { response(envelope(proposal(predicate = "skill"))) }
            val curator = f.curator(this, model = model)
            try { assertFailsWith<IllegalStateException> { curator.curate(f.source) } }
            finally { curator.drain() }
            f.log.crash()
            val restored = f.curator(this, model = model)
            try {
                val retry = restored.curate(f.source)
                assertEquals(0, retry.acceptedReceiptCids.size)
                assertEquals(0, retry.record.quotationSubmittedReceiptCids.size)
                assertEquals(1, retry.record.quotationDuplicateReceiptCids.size)
                assertTrue(retry.unresolvedReasons.values().any { "quotation submission unconfirmed" in it })
                restored.drain(); f.bag.drain(); f.closeSessions()
                assertEquals(Nal.UNIT, f.bag.snapshot().values.single().evidence.positive)
            } finally { restored.drain(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun readyTextRetainsCorrectionsInstructionsAndReceiptsWithoutRerunningModels(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val correctedText = "\uD83D\uDE00\nRain causes floods."
            val corrected = f.source.copy(
                extractedTextCid = f.cas.put(correctedText.encodeToByteArray()), text = correctedText,
                metadata = f.source.metadata + ("uncorrectedTextCid" to listOf(f.source.extractedTextCid.value)),
            )
            val instructions = "Extract source-attributed profile proposals from this synthetic fixture. Retain exact quotes."
            var nlpComplete = false
            var modelCalls = 0
            var sentPrompt: Prompt? = null
            val curator = f.curator(this, nlp = NlpReader {
                assertEquals(correctedText, it)
                svo(it).also { nlpComplete = true }
            }, model = { prompt ->
                assertTrue(nlpComplete)
                modelCalls++
                sentPrompt = prompt
                response(envelope(proposal(begin = 3, end = correctedText.length)))
            })
            try {
                val runner = DocumentCurationLegos.curate(curator)
                val node = LcncNode("profile", DocumentCurationLegos.CURATE, mapOf("instructions" to instructions))
                val output = runner.run(node, mapOf("source" to JsonSupport.parse(
                    JsonSupport.stringify(DocumentCuratorCodec.source(corrected)))))
                val cid = ContentId(output["receiptCid"] as String)
                val record = assertNotNull(curator.record(cid))
                assertEquals(instructions, record.instructions)
                assertEquals(corrected, record.source)
                assertEquals(f.source.originalCid, record.source.originalCid)
                assertEquals("AVAILABLE", output["nlpStatus"])
                assertNotNull(output["sheet"])
                assertEquals(instructions, sentPrompt!!.messages[0].content)
                assertEquals(JsonSupport.stringify(mapOf(
                    "source" to DocumentCuratorCodec.source(record.source),
                    "nlp" to DocumentCuratorCodec.nlp(assertNotNull(record.nlp)),
                )), sentPrompt!!.messages[1].content)
                assertTrue(curator.receipts().view.any { it.a == cid && it.b == record })
                assertEquals(1, record.submittedReceiptCids.size)

                for (invalid in listOf(
                    corrected.copy(originalCid = ContentId.of("missing source".encodeToByteArray())),
                    corrected.copy(extractedTextCid = f.source.extractedTextCid),
                )) {
                    val refused = curator.curate(invalid, instructions)
                    assertNull(refused.record.model)
                    assertEquals(0, refused.submittedReceiptCids.size)
                    assertEquals(0, refused.record.quotationSubmittedReceiptCids.size)
                    assertTrue(refused.unresolvedReasons.view.any { "source CID" in it })
                }
                assertFailsWith<IllegalArgumentException> {
                    runner.run(node.copy(params = mapOf("instructions" to "x".repeat(16_385))),
                        mapOf("source" to corrected))
                }
                assertEquals(1, modelCalls)
                curator.drain()
                val restored = f.curator(this, model = { error("retained reads must not dispatch a model") })
                try {
                    val replayed = assertNotNull(restored.record(cid))
                    assertEquals(corrected, replayed.source)
                    assertEquals(instructions, replayed.instructions)
                    assertEquals(record.model, replayed.model)
                    assertEquals(1, modelCalls)
                    assertTrue(restored.receipts().view.any { it.a == cid })
                    val legacyMap = DocumentCuratorCodec.record(record) - setOf("instructions", "quotationReserved",
                        "quotationSubmitted", "quotationDuplicates") + ("proposals" to record.proposals.values {
                        DocumentCuratorCodec.proposal(it) - setOf("quotationReceiptCid", "quotationBegin", "quotationEnd")
                    })
                    val legacy = DocumentCuratorCodec.decode(CanonicalCbor.encodeMap(legacyMap))
                    assertEquals(DocumentCuratorGrounding.previousInstructions, legacy.instructions)
                    assertNull(legacy.proposals[0].quotationReceiptCid)
                    assertEquals(0, legacy.quotationReservedReceiptCids.size)
                    assertEquals(0, legacy.quotationSubmittedReceiptCids.size)
                    assertEquals(0, legacy.quotationDuplicateReceiptCids.size)
                } finally { restored.drain() }
            } finally { curator.drain(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun attributionAndDedupUseRealIntake(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            var confidence = 0.1
            val curator = f.curator(this, model = { response(envelope(proposal(confidence = confidence))) })
            try {
                val first = curator.curate(f.source)
                confidence = 0.99
                val again = curator.curate(f.source.copy(correlation = "retry"))
                assertEquals(1, first.acceptedReceiptCids.size)
                assertEquals(0, again.acceptedReceiptCids.size)
                assertEquals(first.acceptedReceiptCids[0], again.record.duplicateReceiptCids[0])
                val attribution = first.attributions[0]
                assertTrue((attribution.expression as KifExpr.ListExpr).elements[2] is KifExpr.Quoted)
                assertEquals(NalCopula.PRODUCT, attribution.mapped.copula)
                assertEquals(RelationKind.MATCH, attribution.signal.relation)
                assertEquals(Nal.UNIT, attribution.signal.evidence.positive)
                assertEquals(1, attribution.evidenceBasis.leaves.size)
                assertEquals(f.source.originalCid, attribution.evidenceBasis.leaves[0])
                assertNotNull(f.cas.get(attribution.receiptCid))
                val receipt = CanonicalCbor.decodeMap(f.cas.get(attribution.receiptCid)!!)
                assertEquals(attribution.expression.toKifString(), receipt["expression"])
                assertEquals(Nal.UNIT, (receipt["positiveEvidence"] as Number).toLong())
                assertEquals(listOf(f.source.originalCid.value), receipt["basisLeafCids"])
                curator.drain(); f.bag.drain(); f.closeSessions()
                assertEquals(2, f.bag.size)
                val signal = f.bag.snapshot().values.single { it.provenanceCid == attribution.receiptCid.value }
                assertEquals(Nal.UNIT, signal.evidence.positive)
                assertEquals(0L, signal.evidence.negative)
                assertEquals(attribution.expression.toKifString(), f.bag.glossOf(signal.angular))
                assertFalse(f.bag.snapshot().values.any { it.relation == RelationKind.CAUSALITY })
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun malformedUnsupportedAndHallucinatedStayPending(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val replies = mutableListOf(
                "{not json", envelope(proposal(quote = "Rain prevents floods.")),
                envelope(proposal(modality = "possible")), envelope(proposal(polarity = false)),
                envelope(proposal(predicate = "prevent")),
                envelope(proposal() + ("unsupported" to "retain me")),
            )
            var index = 0
            val curator = f.curator(this, model = { response(replies[index++]) })
            try {
                for (raw in replies) {
                    val result = curator.curate(f.source.copy(correlation = "variant-$index"))
                    assertEquals(0, result.acceptedReceiptCids.size)
                    assertTrue(result.pendingReceiptCids.size > 0)
                    assertTrue(result.unresolvedReasons.size > 0)
                    assertEquals(raw, result.record.model!!.content)
                    assertNotNull(f.cas.get(result.recordCid))
                }
                curator.drain(); f.bag.drain(); f.closeSessions()
                assertEquals(1, f.bag.size)
                assertTrue(f.bag.glossOf(f.bag.snapshot().values.single().angular)!!.startsWith("(quotes "))
                assertEquals(replies.size + 1, curator.records().size) // One quotation reservation and completion.
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun conflictsKeepBothProposalsWithoutMintingSemanticClaims(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val curator = f.curator(this, model = { response(envelope(proposal(), proposal(polarity = false))) })
            try {
                val result = curator.curate(f.source)
                assertEquals(2, result.record.proposals.size)
                assertEquals(2, result.pendingReceiptCids.size)
                assertTrue(result.record.proposals.values().all { p -> p.reasons.values().any { "conflicting" in it } })
                assertEquals(0, result.acceptedReceiptCids.size)
                curator.drain(); f.bag.drain(); f.closeSessions()
                assertEquals(1, f.bag.size)
                assertEquals(1, result.record.quotationSubmittedReceiptCids.size)
                assertEquals(result.record.quotationSubmittedReceiptCids[0], result.record.proposals[0].quotationReceiptCid)
                assertEquals(result.record.proposals[0].quotationReceiptCid, result.record.proposals[1].quotationReceiptCid)
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun dependencyDisagreementIsNotGrounding(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val reader = NlpReader { text ->
                val doc = svo(text)
                doc.copy(sentences = listOf(doc.sentences[0].copy(dependencies = listOf(
                    NlpDependency(0, 2, "root"), NlpDependency(2, 3, "nsubj"), NlpDependency(2, 1, "obj"),
                ).toSeries())).toSeries())
            }
            val curator = f.curator(this, nlp = reader)
            try {
                val result = curator.curate(f.source)
                assertEquals(0, result.acceptedReceiptCids.size)
                assertTrue(result.unresolvedReasons.values().any { "dependency" in it })
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun nlpFailureSkipsModelAndModelFailureRetainsNlpForRetry(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            var failNlp = false
            var failModel = true
            val curator = f.curator(this, nlp = NlpReader { if (failNlp) error("nlp unavailable") else svo(it) },
                model = { if (failModel) error("model unavailable") else response(envelope(proposal())) })
            try {
                val modelFailure = curator.curate(f.source)
                assertNotNull(modelFailure.record.nlp)
                assertTrue(modelFailure.unresolvedReasons.values().any { "model unavailable" in it })
                failNlp = true; failModel = false
                val nlpFailure = curator.curate(f.source)
                assertNull(nlpFailure.record.model)
                assertTrue(nlpFailure.unresolvedReasons.values().any { "skipped: NLP" in it })
                assertEquals(0, nlpFailure.acceptedReceiptCids.size)
                failNlp = false
                assertEquals(1, curator.curate(f.source).acceptedReceiptCids.size)
                assertEquals(0, curator.curate(f.source).acceptedReceiptCids.size)
                curator.drain(); f.bag.drain(); f.closeSessions()
                assertEquals(2, f.bag.size)
                assertTrue(f.bag.snapshot().values.all { it.evidence.positive == Nal.UNIT })
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun metadataUnicodeAndAnsweringIdentitySurviveReplay(): Unit = runBlocking {
        withTimeout(10000) {
            val text = "\uD83D\uDE00\nRain causes floods."
            val f = fixture(this, text)
            val model: suspend (Prompt) -> ModelResponse = { response(envelope(proposal(begin = 3, end = text.length))) }
            val first = f.curator(this, model = model)
            val result = try { first.curate(f.source) } finally { first.drain() }
            val restored = f.curator(this, model = model)
            try {
                val records = restored.records().values()
                val replayed = records.last()
                assertEquals(f.source, replayed.source)
                assertEquals(listOf("one", "two"), replayed.source.metadata["authors"])
                assertEquals(3, replayed.nlp!!.sentences[1].begin)
                assertEquals("\uD83D\uDE00", replayed.nlp.sentences[0].tokens[0].word)
                assertEquals("requested-model", replayed.modelId)
                assertEquals("requested-model", replayed.model!!.modelId)
                assertEquals("requested-model", replayed.model.providerId)
                assertEquals(-1, replayed.model.usage.promptTokens)
                assertEquals(0, restored.curate(f.source).acceptedReceiptCids.size)
                assertEquals(result.acceptedReceiptCids[0], replayed.submittedReceiptCids[0])
            } finally { restored.drain(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun observerFailuresAreNotBusinessFailures(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val curator = f.curator(this, observer = DocumentCuratorObserver { _, _, _ -> error("tap unavailable") })
            try {
                val result = curator.curate(f.source)
                assertEquals(1, result.acceptedReceiptCids.size)
                assertEquals(0, result.pendingReceiptCids.size)
                assertEquals(0, result.unresolvedReasons.size)
                assertEquals(7, result.record.observerFailures.size)
                val duplicate = curator.curate(f.source)
                assertEquals(0, duplicate.acceptedReceiptCids.size)
                assertEquals(5, duplicate.observerFailures.size)
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun pendingRecordObserverFailureSurvivesCasAndReplay(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            var recordTaps = 0
            val curator = f.curator(this, model = { response(envelope(proposal(modality = "possible"))) },
                observer = DocumentCuratorObserver { name, _, _ ->
                    if (name == "curator.record") { recordTaps++; error("record tap unavailable") }
                })
            try {
                val result = try { curator.curate(f.source) } finally { curator.drain() }
                assertEquals(0, result.acceptedReceiptCids.size)
                assertEquals(0, result.record.reservedReceiptCids.size)
                assertTrue(result.pendingReceiptCids.size > 0)
                assertEquals(1, recordTaps)
                val expected = listOf("observer curator.record: record tap unavailable")
                assertEquals(expected, result.observerFailures.values())
                val stored = DocumentCuratorCodec.decode(assertNotNull(f.cas.get(result.recordCid)))
                assertEquals(expected, stored.observerFailures.values())
                assertEquals(expected, result.record.observerFailures.values())
                assertEquals(0, stored.reasons.size)
                val restored = f.curator(this)
                try {
                    val replayed = restored.records().values().last()
                    assertEquals(expected, replayed.observerFailures.values())
                    assertEquals(0, replayed.submittedReceiptCids.size)
                    assertTrue(replayed.proposals[0].reasons.size > 0)
                } finally { restored.drain() }
                f.bag.drain(); f.closeSessions()
                assertEquals(1, f.bag.size)
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun pipelineUsesOwnerContextAndDrainJoinsAcceptedQueueWork(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val nlpStarted = CompletableDeferred<Unit>()
            val modelStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var nlpCalls = 0
            var modelCalls = 0
            val curator = f.curator(this, nlp = NlpReader {
                assertNotNull(currentCoroutineContext()[DocumentCuratorElement])
                nlpCalls++; nlpStarted.complete(Unit); release.await(); svo(it)
            }, model = {
                assertNotNull(currentCoroutineContext()[DocumentCuratorElement])
                modelCalls++
                assertTrue(release.isCompleted)
                val input = JsonSupport.parse(it.messages[it.messages.size - 1].content) as Map<*, *>
                val linguistic = input["nlp"] as Map<*, *>
                assertEquals("Rain causes floods.", linguistic["text"])
                val sentence = (linguistic["sentences"] as List<*>).single() as Map<*, *>
                assertEquals(4, (sentence["tokens"] as List<*>).size)
                assertEquals("Rain causes floods.", (input["source"] as Map<*, *>)["text"])
                modelStarted.complete(Unit); response(envelope(proposal()))
            }, capacity = 1)
            try {
                val requests = (0..5).map { i -> async(start = CoroutineStart.UNDISPATCHED) {
                    curator.curate(f.source.copy(correlation = "queued-$i"))
                } }
                nlpStarted.await()
                assertFalse(modelStarted.isCompleted)
                val drained = async { curator.drain() }
                yield()
                assertFalse(drained.isCompleted)
                release.complete(Unit)
                val results = requests.awaitAll()
                drained.await()
                assertEquals(6, nlpCalls); assertEquals(6, modelCalls)
                assertEquals(1, results.sumOf { it.acceptedReceiptCids.size })
                assertFailsWith<Exception> { curator.curate(f.source) }
            } finally { release.complete(Unit); curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun uncertainSubmissionReplayDoesNotInflateEvidence(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            f.log.failFlushAt = 2
            val curator = f.curator(this)
            try {
                assertFailsWith<IllegalStateException> { curator.curate(f.source) }
            } finally { curator.drain() }
            f.log.crash()
            val restored = f.curator(this)
            try {
                assertTrue(restored.records().values().any { it.reasons.values().any { r -> "unconfirmed" in r } })
                val retry = restored.curate(f.source)
                assertEquals(0, retry.acceptedReceiptCids.size)
                assertTrue(retry.pendingReceiptCids.size > 0)
                restored.drain(); f.bag.drain(); f.closeSessions()
                assertEquals(2, f.bag.size)
                assertTrue(f.bag.snapshot().values.all { it.evidence.positive == Nal.UNIT })
            } finally { restored.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun badSourceCannotMintAndDoesNotStopLaterWork(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val curator = f.curator(this)
            try {
                val invalid = curator.curate(f.source.copy(extractedTextCid = ContentId.of("wrong".encodeToByteArray())))
                assertEquals(0, invalid.acceptedReceiptCids.size)
                assertTrue(invalid.pendingReceiptCids.size > 0)
                assertEquals(1, curator.curate(f.source).acceptedReceiptCids.size)
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun casReadbackFailureDoesNotAppendOrMint(): Unit = runBlocking {
        withTimeout(10000) {
            val cas = object : CasStore() {
                var reject = false
                override fun put(bytes: ByteArray): ContentId {
                    val cid = super.put(bytes)
                    return if (reject) ContentId.of("wrong-cas-key".encodeToByteArray()) else cid
                }
            }
            val f = fixture(this, cas = cas)
            val curator = f.curator(this)
            try {
                cas.reject = true
                assertFailsWith<IllegalStateException> { curator.curate(f.source) }
                assertEquals(0, curator.records().size)
                assertEquals(0, f.bag.size)
                cas.reject = false
                assertEquals(1, curator.curate(f.source).acceptedReceiptCids.size)
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun strictJsonPreservesValidEscapesAndRejectsTrailingInput(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            var raw = envelope(proposal()).replace("\"subject\":\"Rain\"", "\"subject\":\"\\u0052ain\"").replace("0.9", "9e-1")
            val curator = f.curator(this, model = { response(raw) })
            try {
                val valid = curator.curate(f.source)
                assertEquals(1, valid.acceptedReceiptCids.size)
                assertEquals("Rain", valid.record.proposals[0].subject)
                assertEquals(raw, valid.record.model!!.content)
                raw += " trailing"
                val malformed = curator.curate(f.source)
                assertEquals(0, malformed.acceptedReceiptCids.size)
                assertTrue(malformed.pendingReceiptCids.size > 0)
                assertEquals(raw, malformed.record.model!!.content)
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun blockingNlpOverlapsPriorDocumentsModel(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val secondNlpStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            var nlpCalls = 0
            var modelCalls = 0
            val curator = f.curator(this, nlp = NlpReader { text ->
                assertNotNull(currentCoroutineContext()[DocumentCuratorElement])
                if (++nlpCalls == 2) {
                    secondNlpStarted.countDown()
                    check(release.await(3, TimeUnit.SECONDS)) { "prior model could not overlap blocking NLP" }
                }
                svo(text)
            }, model = {
                if (++modelCalls == 1) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        check(secondNlpStarted.await(3, TimeUnit.SECONDS)) { "next document NLP did not start" }
                    }
                    release.countDown()
                }
                response(envelope(proposal()))
            })
            try {
                val requests = (0..1).map { i -> async { curator.curate(f.source.copy(correlation = "overlap-$i")) } }
                assertEquals(1, requests.awaitAll().sumOf { it.acceptedReceiptCids.size })
                assertEquals(2, modelCalls)
            } finally { release.countDown(); curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun invalidNlpIsRetainedAndCannotReachModel(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            var modelCalls = 0
            val curator = f.curator(this, nlp = NlpReader { svo(it).copy(text = "wrong source") }, model = {
                modelCalls++; response(envelope(proposal()))
            })
            try {
                val result = curator.curate(f.source)
                assertEquals(0, modelCalls)
                assertEquals("wrong source", result.record.nlp?.text)
                assertEquals(DocumentNlpStatus.INVALID, result.record.curationIndex().facet(DocumentCurationIndexK.NlpStatus))
                assertEquals(0, result.acceptedReceiptCids.size)
                assertTrue(result.unresolvedReasons.values().any { "skipped: NLP" in it })
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    @Test fun toolOntologyReachesPromptAndPersistedRecord(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val ontology = DocumentCurationToolset.all(listOf(
                "available:forge.document.curate=/api/documents",
                "available:camel.endpoint=timer:lcnc?period=1000",
            ))
            assertTrue(ontology.values().any { it.startsWith("llm-model-base:") })
            assertTrue(ontology.values().any { it.startsWith("lcnc:kg.ingest") })
            assertTrue(ontology.values().any { it.startsWith("lcnc:nal.rule.admit") })
            assertTrue(ontology.values().any { it.startsWith("acp.memory.") })
            var sentPrompt: Prompt? = null
            val curator = f.curator(this, toolOntology = ontology, model = { prompt ->
                sentPrompt = prompt
                response(envelope(proposal()))
            })
            try {
                val result = curator.curate(f.source)
                val payload = JsonSupport.parseMap(assertNotNull(sentPrompt).messages[1].content)
                assertEquals(ontology.values(), payload["toolOntology"])
                assertEquals(ontology.values(), result.record.toolOntology.values())
                assertEquals(ontology.values(), DocumentCuratorCodec.decode(
                    assertNotNull(f.cas.get(result.recordCid))).toolOntology.values())
                assertTrue(result.attributions.values().any { !it.isSemantic })
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    /**
     * Deterministic fixtures (`svo`, hand-authored [NlpDependency] lists) isolate the seam
     * elsewhere in this file; they say nothing about what the real host parser actually
     * emits. These cases run the real installed CoreNLP 4.5.10 guest module (no
     * skip-on-missing-module — the module is expected present) so NlpcoreAxiomatics'
     * negative/modal/passive/reported-speech exclusions are proven against real parses,
     * not a canned model answer.
     */
    @Test fun nlpcoreRecognitionFailsClosedOnRealCoreNlpParses() {
        borg.trikeshed.graal.subvm.CoreNlpRuntime().use { reader ->
            val cases = listOf(
                "Rain causes floods." to 1,
                "Rain does not cause floods." to 0,
                "Rain may cause floods." to 0,
                "Floods are caused by rain." to 0,
                // CoreNLP tags "stops" as a noun and roots this sentence at "If".
                "If rain stops, floods recede." to 0,
                "If Acme pays Beta, Gamma ships Delta." to 0,
                "If customers pay invoices, suppliers deliver goods." to 1,
                "If rain does not fall, crops die." to 0,
                "Heavy rain causes floods." to 0,
                "Rain sometimes causes floods." to 0,
                "Rain caused floods." to 0,
                "Rain and wind cause floods." to 0,
                "Rain causes floods?" to 0,
                "When customers pay invoices, suppliers deliver goods." to 0,
                "Reports say rain causes floods." to 0,
                "\"Rain causes floods,\" the report claimed." to 0,
            )
            for ((text, expected) in cases) {
                val axioms = NlpcoreAxiomatics.recognize(reader.analyze(text), "case-cid")
                assertEquals(expected, axioms.size, "unexpected candidate count for: $text")
                for (i in 0 until axioms.size) {
                    assertEquals("case-cid", axioms[i].rule.provenanceCid, "source provenance retained: $text")
                    assertTrue(axioms[i].rule.isEternal)
                }
            }

            // The causal verb itself carries the relation; a bare-noun rule is faithful.
            val simple = NlpcoreAxiomatics.recognize(reader.analyze("Rain causes floods."), "src")
            assertEquals("Rain", simple[0].rule.antecedent)
            assertEquals("floods", simple[0].rule.consequent)
            assertEquals(900L, simple[0].rule.evidence.positive)

            // A conditional retains BOTH clause predicates — never collapsed to bare
            // "rain ==> floods", which would silently generalize past what the source said.
            val conditional = NlpcoreAxiomatics.recognize(
                reader.analyze("If customers pay invoices, suppliers deliver goods."), "src")
            assertEquals("customers pay invoices", conditional[0].rule.antecedent)
            assertEquals("suppliers deliver goods", conditional[0].rule.consequent)
            assertEquals(800L, conditional[0].rule.evidence.positive)
        }
    }

    @Test fun curationRetainsNlpcoreCandidatesWithoutAutoAdmittingOrRegisteringRawTerms(): Unit = runBlocking {
        borg.trikeshed.graal.subvm.CoreNlpRuntime().use { reader ->
        withTimeout(60000) {
            val f = fixture(this, "Rain causes floods.")
            // A rule an operator already admitted by hand via the separate nal.rule.admit
            // operation — curating a document whose own parser reading would match it must
            // neither duplicate it nor otherwise disturb the live rete.
            val preexisting = EternalRule("Rain", "floods", NalCopula.IMPLICATION,
                EvidenceCoord(Nal.UNIT, 0L), provenanceCid = "hand-admitted")
            val rete = CausalityReteElement(f.bag, listOf(preexisting).toSeries())
            rete.open()
            assertEquals(1, rete.rules.size)
            val curator = f.curator(this, nlp = reader, rete = rete, model = { response(envelope(proposal())) })
            val recordCid: ContentId
            val angular: Long
            try {
                val result = curator.curate(f.source)
                assertEquals(1, result.nlpAxioms.size)
                assertEquals("Rain", result.nlpAxioms[0].rule.antecedent)
                assertEquals("floods", result.nlpAxioms[0].rule.consequent)
                assertEquals(f.source.originalCid.value, result.nlpAxioms[0].rule.provenanceCid)
                // Curation never auto-admits: the live rete is exactly what it was before.
                assertEquals(1, rete.rules.size)
                assertEquals("hand-admitted", rete.rules[0].provenanceCid)
                // The model's (states SOURCE '(PREDICATE SUBJECT OBJECT)) attribution is
                // source-attributed text, not a raw subject/object term pair — it must never
                // land in the rete's term registry.
                val semantic = result.attributions.values().single { it.isSemantic }
                assertNull(rete.termsOf(semantic.signal.angular))
                angular = semantic.signal.angular
                assertEquals(0, rete.fireLive().size)
                recordCid = result.recordCid
            } finally { curator.drain() }
            val restored = f.curator(this, nlp = reader, rete = rete)
            try {
                val replayed = assertNotNull(restored.record(recordCid))
                // Candidates recompute on demand from the retained parse; replay does not
                // invent a global law or re-register raw terms either.
                assertEquals(1, replayed.nlpAxioms.size)
                assertEquals(f.source.originalCid.value, replayed.nlpAxioms[0].rule.provenanceCid)
                assertEquals(1, rete.rules.size)
                assertEquals("hand-admitted", rete.rules[0].provenanceCid)
                assertNull(rete.termsOf(angular))
                assertEquals(0, rete.fireLive().size)
            } finally { restored.drain(); f.bag.drain(); f.closeSessions() }
            assertEquals(0, rete.fireLive().size)
            assertEquals(0L, rete.snapshot().offered)
            assertTrue(f.bag.snapshot().values.any { it.angular == angular })
            rete.drain()
        }
        }
    }

    @Test fun invalidNlpExcludesNlpcoreCandidatesButRetainsTheRawParse(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val curator = f.curator(this, nlp = NlpReader { svo(it).copy(text = "wrong source") })
            try {
                val result = curator.curate(f.source)
                assertEquals(0, result.nlpAxioms.size)
                assertNotNull(result.record.nlp)
                assertEquals(DocumentNlpStatus.INVALID, result.record.curationIndex().facet(DocumentCurationIndexK.NlpStatus))
            } finally { curator.close(); f.bag.drain(); f.closeSessions() }
        }
    }

    private class Log : DurableAppendLog {
        private val frames = mutableListOf<Pair<Long, ByteArray>>()
        private var committed = 0
        private var flushes = 0
        var failFlushAt = -1
        override fun append(sequence: Long, payload: ByteArray): Long {
            frames.add(sequence to payload.copyOf()); return sequence
        }
        override fun flush() {
            flushes++
            check(flushes != failFlushAt) { "simulated flush failure" }
            committed = frames.size
        }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for ((seq, frame) in frames.take(committed)) onFrame(seq, frame.copyOf())
            return frames.take(committed).lastOrNull()?.first ?: 0L
        }
        fun crash() { while (frames.size > committed) frames.removeAt(frames.lastIndex); failFlushAt = -1 }
        override fun injectCorruptionAfter(sequence: Long) = error("not used")
    }

    private data class Fixture(val cas: CasStore, val log: Log, val bag: BeliefBagElement, val source: DocumentSource,
        val sessions: MutableList<DocumentModelFixture.Session> = mutableListOf()) {
        suspend fun curator(scope: CoroutineScope, nlp: NlpReader = NlpReader(::svo),
            model: suspend (Prompt) -> ModelResponse = { response(envelope(proposal())) },
            observer: DocumentCuratorObserver? = null, capacity: Int = 2,
            rete: CausalityReteElement? = null,
            toolOntology: borg.trikeshed.modelmux.ToolOntologyScaffold = emptySeriesOf()): DocumentCuratorElement {
            val session = DocumentModelFixture.open(modelId = "requested-model", responder = model)
            sessions.add(session)
            return DocumentCuratorElement.create(CoroutineScope(scope.coroutineContext + session.htx + session.reactor),
                nlp, session.model, "requested-model", cas, log, bag, observer, capacity,
                rete = rete, toolOntology = toolOntology)
        }

        /** Closes every fixture transport opened by [curator]; safe to call more than once. */
        suspend fun closeSessions() {
            val opened = sessions.toList()
            sessions.clear()
            for (session in opened) session.close()
        }
    }

    private suspend fun fixture(scope: CoroutineScope, text: String = "Rain causes floods.", cas: CasStore = CasStore.inMemory()): Fixture {
        val source = DocumentSource(cas.put("original document bytes".encodeToByteArray()),
            cas.put(text.encodeToByteArray()), text, "source.txt", "text/plain", "route-1",
            mapOf("authors" to listOf("one", "two"), "title" to listOf("\uD83D\uDE00 title")))
        val bag = BeliefBagElement(capacity = 64, cas = cas)
        bag.open()
        return Fixture(cas, Log(), bag, source)
    }

    companion object {
        private fun response(content: String) = ModelResponse(content, ModelUsage(-1, -1, -1), "actual-provider", "answering-model")
        private fun envelope(vararg proposals: Map<String, Any?>) = JsonSupport.stringify(mapOf("format" to "TRIPLET_JSON", "triplets" to proposals.toList()))
        private fun proposal(confidence: Double = 0.9, quote: String = "Rain causes floods.", begin: Int = 0,
            end: Int = 19, polarity: Boolean = true, modality: String = "asserted", predicate: String = "cause") =
            mapOf("subject" to "Rain", "predicate" to predicate, "object" to "floods", "confidence" to confidence,
                "quote" to quote, "begin" to begin, "end" to end, "polarity" to polarity, "modality" to modality)

        private fun svo(text: String): NlpDocument {
            val begin = text.indexOf("Rain")
            val tokens = listOf(NlpToken(1, begin, begin + 4, "Rain", "rain", "NN", "O"),
                NlpToken(2, begin + 5, begin + 11, "causes", "cause", "VBZ", "O"),
                NlpToken(3, begin + 12, begin + 18, "floods", "flood", "NNS", "O"),
                NlpToken(4, begin + 18, begin + 19, ".", ".", ".", "O"))
            val deps = listOf(NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
                NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct"))
            val prefix = text.substring(0, begin).trimEnd()
            val sentence = NlpSentence(if (prefix.isEmpty()) 0 else 1, begin, begin + 19,
                tokens.toSeries(), deps.toSeries())
            val sentences = if (prefix.isEmpty()) listOf(sentence) else listOf(
                NlpSentence(0, 0, prefix.length,
                    listOf(NlpToken(1, 0, prefix.length, prefix, prefix, "SYM", "O")).toSeries(),
                    listOf(NlpDependency(0, 1, "root")).toSeries()), sentence)
            return NlpDocument(text, sentences.toSeries())
        }

        private fun documentTokens(text: String): NlpDocument {
            val tokens = Regex("\\S+").findAll(text).mapIndexed { index, match ->
                NlpToken(index + 1, match.range.first, match.range.last + 1, match.value, match.value, "NN", "O")
            }.toList()
            return NlpDocument(text, listOf(NlpSentence(0, 0, text.length, tokens.toSeries(),
                listOf(NlpDependency(0, 1, "root")).toSeries())).toSeries())
        }
    }
}

/** Direct runner for source-overlay verification while unrelated root migration callers are broken. */
object DocumentCuratorTestMain {
    @JvmStatic fun main(args: Array<String>) {
        val test = DocumentCuratorTest()
        val checks = listOf(
            "retained envelope without format" to test::retainedTripletEnvelopeWithoutFormatReachesBothGroundingPaths,
            "profile quotation/replay" to test::profileQuotationReachesIntakeWithoutAdmittingInterpretationOrReinforcingOnReplay,
            "unverified quotations" to test::missingAndAmbiguousQuotationsRemainPendingWithoutIntake,
            "uncertain quotation delivery" to test::quotationReservationSuppressesUncertainDeliveryAfterReplay,
            "attribution/dedup" to test::attributionAndDedupUseRealIntake,
            "pending" to test::malformedUnsupportedAndHallucinatedStayPending,
            "conflict" to test::conflictsKeepBothProposalsWithoutMintingSemanticClaims,
            "dependency disagreement" to test::dependencyDisagreementIsNotGrounding,
            "NLP prerequisite/failure/retry" to test::nlpFailureSkipsModelAndModelFailureRetainsNlpForRetry,
            "replay/provenance" to test::metadataUnicodeAndAnsweringIdentitySurviveReplay,
            "observer isolation" to test::observerFailuresAreNotBusinessFailures,
            "pending observer persistence" to test::pendingRecordObserverFailureSurvivesCasAndReplay,
            "NLP prompt/pipeline/drain" to test::pipelineUsesOwnerContextAndDrainJoinsAcceptedQueueWork,
            "uncertain submission" to test::uncertainSubmissionReplayDoesNotInflateEvidence,
            "source validation" to test::badSourceCannotMintAndDoesNotStopLaterWork,
            "CAS readback" to test::casReadbackFailureDoesNotAppendOrMint,
            "strict JSON" to test::strictJsonPreservesValidEscapesAndRejectsTrailingInput,
            "blocking NLP document overlap" to test::blockingNlpOverlapsPriorDocumentsModel,
            "invalid NLP excludes model" to test::invalidNlpIsRetainedAndCannotReachModel,
        )
        for ((name, check) in checks) { check(); println("PASS $name") }
        println("PASS ${checks.size} DocumentCurator checks")
    }
}
