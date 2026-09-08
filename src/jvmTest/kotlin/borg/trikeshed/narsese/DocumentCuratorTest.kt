package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.modelmux.ModelResponse
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
                curator.drain(); f.bag.drain()
                assertEquals(1, f.bag.size)
                val signal = f.bag.snapshot().values.single()
                assertEquals(Nal.UNIT, signal.evidence.positive)
                assertEquals(0L, signal.evidence.negative)
                assertEquals(attribution.expression.toKifString(), f.bag.glossOf(signal.angular))
                assertFalse(f.bag.snapshot().values.any { it.relation == RelationKind.CAUSALITY })
            } finally { curator.close(); f.bag.drain() }
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
                    val result = curator.curate(f.source)
                    assertEquals(0, result.acceptedReceiptCids.size)
                    assertTrue(result.pendingReceiptCids.size > 0)
                    assertTrue(result.unresolvedReasons.size > 0)
                    assertEquals(raw, result.record.model!!.content)
                    assertNotNull(f.cas.get(result.recordCid))
                }
                curator.drain(); f.bag.drain()
                assertEquals(0, f.bag.size)
                assertEquals(replies.size, curator.records().size)
            } finally { curator.close(); f.bag.drain() }
        }
    }

    @Test fun conflictsKeepBothProposalsWithoutMinting(): Unit = runBlocking {
        withTimeout(10000) {
            val f = fixture(this)
            val curator = f.curator(this, model = { response(envelope(proposal(), proposal(polarity = false))) })
            try {
                val result = curator.curate(f.source)
                assertEquals(2, result.record.proposals.size)
                assertEquals(2, result.pendingReceiptCids.size)
                assertTrue(result.record.proposals.values().all { p -> p.reasons.values().any { "conflicting" in it } })
                assertEquals(0, result.acceptedReceiptCids.size)
                curator.drain(); f.bag.drain()
                assertEquals(0, f.bag.size)
            } finally { curator.close(); f.bag.drain() }
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
            } finally { curator.close(); f.bag.drain() }
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
                curator.drain(); f.bag.drain()
                assertEquals(Nal.UNIT, f.bag.snapshot().values.single().evidence.positive)
            } finally { curator.close(); f.bag.drain() }
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
                assertEquals(3, replayed.nlp!!.sentences[0].begin)
                assertEquals("requested-model", replayed.modelId)
                assertEquals("answering-model", replayed.model!!.modelId)
                assertEquals("actual-provider", replayed.model.providerId)
                assertEquals(-1, replayed.model.usage.promptTokens)
                assertEquals(0, restored.curate(f.source).acceptedReceiptCids.size)
                assertEquals(result.acceptedReceiptCids[0], replayed.submittedReceiptCids[0])
            } finally { restored.drain(); f.bag.drain() }
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
                assertEquals(6, result.record.observerFailures.size)
                val duplicate = curator.curate(f.source)
                assertEquals(0, duplicate.acceptedReceiptCids.size)
                assertEquals(5, duplicate.observerFailures.size)
            } finally { curator.close(); f.bag.drain() }
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
                assertEquals(0, f.bag.size)
            } finally { curator.close(); f.bag.drain() }
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
                val linguistic = input["linguistics"] as Map<*, *>
                assertEquals("AVAILABLE", linguistic["status"])
                assertNull(linguistic["parseConfidence"])
                assertEquals("UNAVAILABLE", linguistic["externalVerification"])
                val sentence = (linguistic["sentences"] as List<*>).single() as Map<*, *>
                assertEquals(4, (sentence["tokens"] as List<*>).size)
                assertEquals("Rain causes floods.", input["text"])
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
            } finally { release.complete(Unit); curator.close(); f.bag.drain() }
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
                restored.drain(); f.bag.drain()
                assertEquals(1, f.bag.size)
                assertEquals(Nal.UNIT, f.bag.snapshot().values.single().evidence.positive)
            } finally { restored.close(); f.bag.drain() }
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
            } finally { curator.close(); f.bag.drain() }
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
            } finally { curator.close(); f.bag.drain() }
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
            } finally { curator.close(); f.bag.drain() }
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
            } finally { release.countDown(); curator.close(); f.bag.drain() }
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
            } finally { curator.close(); f.bag.drain() }
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

    private data class Fixture(val cas: CasStore, val log: Log, val bag: BeliefBagElement, val source: DocumentSource) {
        suspend fun curator(scope: CoroutineScope, nlp: NlpReader = NlpReader(::svo),
            model: suspend (Prompt) -> ModelResponse = { response(envelope(proposal())) },
            observer: DocumentCuratorObserver? = null, capacity: Int = 2): DocumentCuratorElement =
            DocumentCuratorElement.create(scope, nlp, model, "requested-model", cas, log, bag, observer, capacity)
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
            return NlpDocument(text, listOf(NlpSentence(0, begin, begin + 19, tokens.toSeries(), deps.toSeries())).toSeries())
        }
    }
}

/** Direct runner for source-overlay verification while unrelated root migration callers are broken. */
object DocumentCuratorTestMain {
    @JvmStatic fun main(args: Array<String>) {
        val test = DocumentCuratorTest()
        val checks = listOf(
            "attribution/dedup" to test::attributionAndDedupUseRealIntake,
            "pending" to test::malformedUnsupportedAndHallucinatedStayPending,
            "conflict" to test::conflictsKeepBothProposalsWithoutMinting,
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
