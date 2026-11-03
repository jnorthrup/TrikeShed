package borg.trikeshed.forge.server

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class PiBreadcrumbWireTest {
    @Test fun existingContextIsSharedWithoutOverwritingAnotherPiSession() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val contexts = BreadcrumbElement()
        contexts.transact("shared") { it.put(BreadcrumbId("hermes"), "hermes", "existing evidence") }
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", caps = _s["chat"], baseUrl = "https://fixture.invalid/v1")
            defaultModel("fixture")
        }
        val token = "pi-test-token-0123456789abcdef"
        val seen = CopyOnWriteArrayList<String>()
        val wire = PiWire(owner, EmptyCoroutineContext, contexts, { mux }, ContentId.of(token.encodeToByteArray()).hex,
            openPort = { _, generation ->
                seen.add(generation.session)
                object : PiWire.Port {
                    override fun prompt(text: String): Map<String, Any?> = _m["messages" j _l[_m["role" j "assistant", "content" j text]]]
                    override fun close() { }
                }
            })
        suspend fun call(method: String, path: String, body: String = ""): Map<*, *> {
            val raw = "$method $path HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\n\r\n$body"
            val response = assertNotNull(wire.route(method, path, raw, null))
            assertTrue(response.status in 200..299, response.body)
            return assertNotNull(reifyStrict(response.body) as? Map<*, *>)
        }
        suspend fun ready(id: String) = withTimeout(10_000) {
            while (true) {
                val state = call("GET", "/api/pi/session/status?sessionId=$id")
                if (state["state"] == "ready") return@withTimeout state
                if (state["state"] == "failed") error(state.toString())
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") emptyMap<Any, Any>()
        }
        try {
            val first = call("POST", "/api/pi/session/start", """{"model":"fixture","contextId":"shared"}""")
            val second = call("POST", "/api/pi/session/start", """{"model":"fixture","contextId":"shared"}""")
            val a = first["sessionId"] as String; val b = second["sessionId"] as String
            assertEquals("shared", ready(a)["contextId"]); assertEquals("shared", ready(b)["contextId"])
            call("POST", "/api/pi/session/prompt", """{"sessionId":"$a","text":"first input"}""")
            call("POST", "/api/pi/session/prompt", """{"sessionId":"$b","text":"second input"}""")
            ready(a); ready(b)
            val state = contexts.transact("shared", create = false) { it.state() }
            assertTrue(state.contains("existing evidence")); assertTrue(state.contains("first input")); assertTrue(state.contains("second input"))
            assertTrue(state.contains("$a-input-1")); assertTrue(state.contains("$b-input-1"))
            assertEquals(_l["shared", "shared"], seen.sorted())
        } finally { wire.drain(); owner.cancel() }
    }
}
