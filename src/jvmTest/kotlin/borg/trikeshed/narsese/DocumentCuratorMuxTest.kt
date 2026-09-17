package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.modelmux.PromptMessage
import borg.trikeshed.userspace.reactor.MuxReactorElement
import keymux.KeyMux
import keymux.TestKeySource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import modelmux.ModelMux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FIRST PROOF of Order 13: a document curation call reaches a model ONLY through an
 * OPEN/ACTIVE [MuxReactorElement] whose [MuxReactorElement.modelMux] shares the SAME
 * [KeyMux] identity as [MuxReactorElement.keyMux], via [DocumentModel.through] — never
 * a raw callback. The provider itself is a deterministic HTX fake (no network), so this
 * proves the DOORWAY, not live provider behaviour.
 */
class DocumentCuratorMuxTest {

    private class Log : DurableAppendLog {
        private val frames = mutableListOf<Pair<Long, ByteArray>>()
        override fun append(sequence: Long, payload: ByteArray): Long { frames.add(sequence to payload.copyOf()); return sequence }
        override fun flush() {}
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for ((seq, frame) in frames) onFrame(seq, frame.copyOf())
            return frames.lastOrNull()?.first ?: 0L
        }
        override fun injectCorruptionAfter(sequence: Long) = error("not used")
    }

    private fun tokens(text: String): NlpDocument {
        val begin = text.indexOf("Acme")
        val doc = listOf(NlpToken(1, begin, begin + 4, "Acme", "acme", "NN", "O"),
            NlpToken(2, begin + 5, begin + 9, "pays", "pay", "VBZ", "O"),
            NlpToken(3, begin + 10, begin + 14, "Beta", "beta", "NN", "O"),
            NlpToken(4, begin + 14, begin + 15, ".", ".", ".", "O"))
        val deps = listOf(NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct"))
        return NlpDocument(text, listOf(NlpSentence(0, 0, text.length, doc.toSeries(), deps.toSeries())).toSeries())
    }

    @Test
    fun realModelMuxChatUnderFixtureKeyMuxProducesAndPersistsAReceipt(): Unit = runBlocking {
        withTimeout(10_000) {
            val text = "Acme pays Beta."
            val triplet = """{"format":"TRIPLET_JSON","triplets":[{"subject":"Acme","predicate":"pay","object":"Beta","confidence":0.9,"quote":"$text","begin":0,"end":${text.length},"polarity":true,"modality":"asserted"}]}"""
            var httpCalls = 0
            var outbound: HtxRequest? = null
            val routeService = object : HtxRouteService {
                override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                    httpCalls++
                    outbound = request
                    val response = HtxResponse(status = 200, body = ByteSeries(
                        """{"choices":[{"message":{"content":${jsonQuote(triplet)}}}],"usage":{"prompt_tokens":11,"completion_tokens":7}}"""
                            .encodeToByteArray()))
                    return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
                }
            }
            val htx = openHtxElement(routeService = routeService)
            try {
                val keyMux = KeyMux { bind("llm.doc-model.key", TestKeySource()) }
                val mux = ModelMux(keyMux) { model("doc-model", caps = setOf("chat")) }
                val reactor = MuxReactorElement(keyMux = keyMux, modelMuxProvider = { mux })
                reactor.open()
                try {
                    val context = htx + reactor
                    val model = DocumentModel.through(context)
                    val cas = CasStore.inMemory()
                    val bag = BeliefBagElement(capacity = 8, cas = cas)
                    bag.open()
                    val source = DocumentSource(cas.put("original bytes".encodeToByteArray()),
                        cas.put(text.encodeToByteArray()), text, "acme.txt", "text/plain", "mux-proof")
                    val curator = DocumentCuratorElement.create(CoroutineScope(coroutineContext + context),
                        NlpReader { tokens(it) }, model, "doc-model", cas, Log(), bag)
                    try {
                        val result = withContext(context) { curator.curate(source) }

                        // Real chat hit the HTX provider exactly once (no bypass).
                        assertEquals(1, httpCalls)

                        // The exact per-call receipt is retained on the record — not a shared lastReceipt.
                        val receipt = assertNotNull(result.record.receipt, "receipt must be retained on the record")
                        assertEquals("doc-model", receipt.modelId)
                        assertEquals("doc-model", receipt.providerId)
                        assertEquals(200, receipt.httpStatus)
                        assertEquals("chat", receipt.action)
                        assertEquals(11, receipt.inputTokens)
                        assertEquals(7, receipt.outputTokens)
                        val sent = assertNotNull(outbound)
                        assertEquals("Bearer sk-test", sent.headers.view.first { it.a.equals("Authorization", true) }.b)
                        assertEquals(ContentId.of(sent.body.toArray()).value, receipt.requestHash)
                        assertEquals(source.correlation, receipt.assessmentId)
                        assertNotNull(receipt.sessionId)
                        assertEquals(false, receipt.cachedHit)
                        assertEquals("llm.doc-model.key", reactor.flowState.value.keys.single().keyId)

                        // Source-grounded proposal, submitted and replayable byte-for-byte through the codec.
                        assertEquals(1, result.record.submittedReceiptCids.size)
                        assertEquals(triplet, result.record.model!!.content)
                        val stored = DocumentCuratorCodec.decode(assertNotNull(cas.get(result.recordCid)))
                        assertEquals(receipt, stored.receipt)
                        assertEquals(triplet, stored.model!!.content)

                        curator.drain(); bag.drain()
                    } finally { curator.close(); bag.drain() }
                } finally { reactor.close() }

                // Closed reactor: the doorway itself refuses, never a raw fallback.
                val closedReactor = MuxReactorElement(keyMux = keyMux, modelMuxProvider = { mux })
                closedReactor.open(); closedReactor.close()
                val closedModel = DocumentModel.through(closedReactor)
                assertFailsWith<IllegalStateException> {
                    closedModel(Prompt(listOf(PromptMessage.User("x")).toSeries(), "doc-model"))
                }

                // Missing key: KeyMux with no binding for the model → ModelMux refuses admission, no fake success.
                val emptyKeyMux = KeyMux {}
                val emptyMux = ModelMux(emptyKeyMux) { model("doc-model", caps = setOf("chat")) }
                val keylessReactor = MuxReactorElement(keyMux = emptyKeyMux, modelMuxProvider = { emptyMux })
                keylessReactor.open()
                try {
                    val keylessModel = DocumentModel.through(keylessReactor)
                    val rejected = assertFailsWith<DocumentModelFailure> {
                        withContext(htx) { keylessModel(Prompt(listOf(PromptMessage.User("x")).toSeries(), "doc-model")) }
                    }
                    assertEquals(0, rejected.receipt.httpStatus)
                    assertTrue(rejected.receipt.errorMessage.orEmpty().startsWith("need(key)"))
                    assertEquals(1, httpCalls)
                } finally { keylessReactor.close() }
            } finally { htx.close() }
        }
    }

    private fun jsonQuote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
