package muxcontract

import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.lcnc.BrainMuxNodes
import borg.trikeshed.lcnc.FrameIdChain
import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncScopeFrame
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.reactor.MuxReactorElement
import keymux.KeyMux
import keymux.TestKeySource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import modelmux.ModelMux
import modelmux.MuxActivity
import modelmux.MuxCallContext
import modelmux.MuxCallRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrainMuxNodesContractTest {
    private fun mux(): ModelMux = ModelMux(KeyMux {
        bind("llm.primary-provider.key", TestKeySource(value = "fixture-primary"))
        bind("llm.secondary.key", TestKeySource(value = "fixture-secondary"))
    }) {
        model("primary", caps = setOf("chat"), provider = "primary-provider")
        model("secondary", caps = setOf("chat"), provider = "secondary")
    }

    @Test
    fun catalogDefaultIsIndependentOfPreviousExplicitModel() = runTest {
        val transport = FakeTransport { _, _ -> FakeTransport.chatJson("answer") }
        val htx = openHtxElement(routeService = transport)
        val mux = mux()
        val runner = BrainMuxNodes.registry(modelMuxProvider = { mux }).getValue("prompt.chat")
        try {
            withContext(htx) {
                val explicit = runner.run(LcncNode("chat", "prompt.chat", mapOf("model" to "secondary")), mapOf("prompt" to "first"))
                assertEquals("secondary", explicit["model"])
                val default = runner.run(LcncNode("chat", "prompt.chat"), mapOf("prompt" to "second"))
                assertEquals("primary", default["model"])
                assertTrue(default["ok"] == true)
                assertEquals("llm.primary-provider.key", (default["receipt"] as Map<*, *>)["keyId"])
                assertEquals("primary-provider", mux.lastSelection?.provider)
            }
            assertEquals(2, transport.calls)
        } finally {
            htx.close()
        }
    }

    @Test
    fun invocationReceiptSurvivesInterleavedCalls() = runTest {
        val transport = FakeTransport { _, _ -> FakeTransport.chatJson("answer", 3, 4) }
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement()
        val mux = mux()
        val runner = BrainMuxNodes.registry(modelMux = mux).getValue("prompt.chat")
        val activity = MuxActivity()
        val observed = mutableListOf<MuxCallRecord>()
        val attribution = MuxCallContext(11, 22, activity) { record ->
            observed.add(record)
            // Complete another invocation before the LCNC caller resumes.
            val interleaved = launch(htx + reactor + MuxCallContext(), start = CoroutineStart.UNDISPATCHED) {
                val prompt = if (record.cachedHit) "interleaved" else "warm"
                mux.chat("primary", 1 j { "user" j prompt }, maxTokens = 256, temperature = 0.2, contextId = "chat").getOrThrow()
            }
            assertTrue(interleaved.isCompleted)
        }
        val firstFrame = LcncScopeFrame(emptyMap(), chain = FrameIdChain.root("first"))
        val secondFrame = LcncScopeFrame(emptyMap(), chain = FrameIdChain.root("second"))
        val node = LcncNode("chat", "prompt.chat")
        try {
            withContext(htx + reactor) {
                mux.chat("primary", 1 j { "user" j "warm" }, maxTokens = 256, temperature = 0.2, contextId = "chat").getOrThrow()
            }
            withContext(htx + reactor + attribution) {
                val first = withContext(firstFrame) { runner.run(node, mapOf("prompt" to "same")) }
                val second = withContext(secondFrame) { runner.run(node, mapOf("prompt" to "same")) }
                val a = first["receipt"] as Map<*, *>
                val b = second["receipt"] as Map<*, *>
                assertFalse(first["cachedHit"] as Boolean)
                assertTrue(second["cachedHit"] as Boolean)
                assertNotEquals(a["id"], b["id"])
                assertEquals(firstFrame.chain.cid.value, a["scopeCid"])
                assertEquals(secondFrame.chain.cid.value, b["scopeCid"])
                assertEquals(11L, a["conversationId"])
                assertEquals(22L, b["turnId"])
                assertEquals("primary-provider", a["provider"])
                assertEquals("llm.primary-provider.key", b["keyId"])
                assertEquals(3, a["inputTokens"])
                assertEquals(4, a["outputTokens"])
                assertEquals(21L, mux.quotaStandings(kotlinx.datetime.Clock.System.now().toEpochMilliseconds()).single().spent)
            }
            assertEquals(3, transport.calls)
            assertEquals(observed, activity.snapshot())
            assertEquals(2, observed.size)
            assertTrue("receipt" in requireNotNull(LcncContracts.find("prompt.chat")).outputs)
        } finally {
            htx.close()
            reactor.close()
        }
    }

    @Test
    fun failureRetainsBindingAndDoesNotInventUsage() = runTest {
        val htx = openHtxElement(routeService = FakeTransport { _, _ -> FakeTransport.status(401) })
        val runner = BrainMuxNodes.registry(modelMux = mux()).getValue("prompt.chat")
        try {
            val output = withContext(htx) { runner.run(LcncNode("chat", "prompt.chat"), mapOf("prompt" to "request")) }
            val receipt = output["receipt"] as Map<*, *>
            assertFalse(output["ok"] as Boolean)
            assertEquals("failed", receipt["status"])
            assertEquals(401, receipt["httpStatus"])
            assertEquals("llm.primary-provider.key", receipt["keyId"])
            assertNull(receipt["inputTokens"])
            assertNull(receipt["outputTokens"])
            assertNull(receipt["conversationId"])
            assertFalse("inputTokens" in output)
        } finally {
            htx.close()
        }
    }

    @Test
    fun emptyCatalogDoesNotSelectAnUnconfiguredModel() = runTest {
        val runner = BrainMuxNodes.registry(modelMux = ModelMux(KeyMux {}) {}).getValue("prompt.chat")
        val output = runner.run(LcncNode("chat", "prompt.chat"), mapOf("prompt" to "request"))
        assertFalse(output["ok"] as Boolean)
        assertEquals("", output["model"])
        assertFalse("receipt" in output)
    }
}
