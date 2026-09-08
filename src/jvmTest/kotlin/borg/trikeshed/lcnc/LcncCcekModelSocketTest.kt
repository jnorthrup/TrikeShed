package borg.trikeshed.lcnc

import borg.trikeshed.ccek.CCEK
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.lcnc.ccek.LcncCcekAssembly
import borg.trikeshed.lib.s_
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.reactor.MuxReactorElement
import com.sun.net.httpserver.HttpServer
import keymux.ApiSource
import keymux.KeyMux
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import modelmux.ModelMux
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LcncCcekModelSocketTest {
    @Test
    fun nestedScopeResolvesKeysAndCallsModelThroughInheritedHtx() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val keyReads = AtomicInteger()
        val received = CompletableDeferred<Map<String, Any?>>()
        server.createContext("/keys/llm.local.key") { exchange ->
            exchange.use {
                keyReads.incrementAndGet()
                val body = "loopback-key".encodeToByteArray()
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.use {
                received.complete(mapOf(
                    "method" to it.requestMethod,
                    "authorization" to it.requestHeaders.getFirst("Authorization"),
                    "body" to JsonSupport.parse(it.requestBody.readBytes().decodeToString()),
                ))
                val body = """{"choices":[{"message":{"content":"socket answered"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}""".encodeToByteArray()
                it.responseHeaders.set("Content-Type", "application/json")
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        server.start()
        val htx = openHtxElement()
        val base = "http://127.0.0.1:${server.address.port}"
        val keys = KeyMux { bind("llm.local.key", ApiSource("$base/keys")) }
        val mux = ModelMux(keys) {
            model("local-model", caps = setOf("chat"), provider = "local", baseUrl = "$base/v1")
        }
        val reactor = MuxReactorElement(keyMux = keys, modelMuxProvider = { mux }).also { it.open() }
        try {
            val registry = BrainMuxNodes.registry()
            val chat = LcncNode("chat", "prompt.chat", params = mapOf(
                "model" to "local-model", "prompt" to "answer through the socket", "maxTokens" to "8",
            ))

            // The old reactor-only context fails before either socket request.
            val absent = withContext(reactor) { registry.getValue("prompt.chat").run(chat, emptyMap()) }
            assertEquals(false, absent["ok"])
            assertTrue(absent["error"].toString().contains("No HtxKey"))
            assertEquals(0, keyReads.get())
            assertFalse(received.isCompleted)

            val binding = CCEK.initialize(Dispatchers.Default + htx + reactor)
            val program = LcncProgram("socket", s_[
                LcncNode("ring", LcncContracts.SCOPE, children = s_[
                    chat,
                    LcncNode("answer", LcncContracts.SCOPE_OUT, params = mapOf("name" to "answer")),
                ]),
            ], s_[LcncWire("chat", "content", "answer", "value")])
            val run = LcncCcekAssembly(binding, LcncRunner(registry)).launch("socket", program)
            try {
                val result = withTimeout(15_000) { run.result.await() }
                assertEquals("socket answered", (result.nodeOutputs.getValue("ring") as Map<*, *>)["answer"])
                val request = withTimeout(1_000) { received.await() }
                assertEquals("POST", request["method"])
                assertEquals("Bearer loopback-key", request["authorization"])
                assertEquals("local-model", (request["body"] as Map<*, *>)["model"])
                assertTrue(keyReads.get() > 0)
                assertEquals(200, mux.lastReceipt?.httpStatus)
                assertEquals(false, mux.lastReceipt?.cachedHit)
                assertEquals(3, mux.lastReceipt?.inputTokens)
                assertEquals(2, mux.lastReceipt?.outputTokens)
            } finally {
                run.node.stop()
                run.cancel()
            }
        } finally {
            reactor.close()
            htx.close()
            server.stop(0)
        }
    }
}
