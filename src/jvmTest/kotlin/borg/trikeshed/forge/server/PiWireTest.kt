package borg.trikeshed.forge.server

import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class PiWireTest {
    private val token = "operator-pi-fixture-token-01234567890123456789"
    private fun catalog() = ModelMux(KeyMux { }) { model("platform-fixture", caps = _s["chat"]) }
    private fun request(method: String, path: String, body: Map<String, Any?>? = null, auth: Boolean = true,
        origin: String = "http://localhost:8080"): String =
        "$method $path HTTP/1.1\r\nHost: localhost:8080\r\nOrigin: $origin\r\n" +
            (if (auth) "Authorization: Bearer $token\r\n" else "") + "\r\n" + (body?.let(::jsonOf) ?: "")
    private suspend fun call(wire: PiWire, method: String, path: String, body: Map<String, Any?>? = null,
        auth: Boolean = true, origin: String = "http://localhost:8080") =
        requireNotNull(wire.route(method, path, request(method, path, body, auth, origin), null))
    private fun body(response: borg.trikeshed.litebike.JvmKanbanServer.HttpResponse) = reifyMap(response.body)
    private suspend fun status(wire: PiWire, id: String) = body(call(wire, "GET", "/api/pi/session/status?sessionId=$id"))
    private suspend fun awaitState(wire: PiWire, id: String, state: String) = withTimeout(5_000) {
        while (status(wire, id)["state"] != state) yield()
    }

    @Test fun authAndExplicitCatalogSelectionFailClosed() = runBlocking {
        val contexts = BreadcrumbElement()
        val absent = PiWire(this, EmptyCoroutineContext, contexts, { catalog() }, null)
        val wire = PiWire(this, EmptyCoroutineContext, contexts, { catalog() }, ContentId.of(token.encodeToByteArray()).hex,
            openPort = { _, _ -> error("must not open") })
        try {
            assertEquals(503, call(absent, "GET", "/api/pi/models").status)
            assertEquals(401, call(wire, "GET", "/api/pi/models", auth = false).status)
            assertEquals(403, call(wire, "GET", "/api/pi/models", origin = "https://foreign.invalid").status)
            assertEquals(200, call(wire, "GET", "/api/pi/models").status)
            assertEquals(400, call(wire, "POST", "/api/pi/session/start", _m["model" j "foreign-model"]).status)
            assertEquals(400, call(wire, "POST", "/api/pi/session/start", _m["model" j "platform-fixture", "sessionId" j "foreign"]).status)
            assertNull(wire.route("GET", "/api/hermes/terminal", "", null))
        } finally { absent.drain(); wire.drain() }
    }

    @Test fun closeDrainsAcceptedPromptSerializesChildCloseAndRetainsBreadcrumbTranscript() = runBlocking {
        val contexts = BreadcrumbElement(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var active = false; var closed = false
        val wire = PiWire(this, EmptyCoroutineContext, contexts, { catalog() }, ContentId.of(token.encodeToByteArray()).hex,
            openPort = { model, generation ->
                assertEquals("platform-fixture", model)
                object : PiWire.Port {
                    override fun prompt(text: String): Map<String, Any?> = runBlocking {
                        generation.execute { active = true; started.complete(Unit); release.await(); active = false
                            _m["messages" j _l[_m["role" j "toolResult", "content" j _l[_m["type" j "text", "text" j "tool evidence"]]],
                                _m["role" j "assistant", "content" j _l[_m["type" j "text", "text" j "Pi answer"]]]], "buffered" j true] }
                    }
                    override fun close() { check(!active); closed = true }
                }
            })
        try {
            val start = call(wire, "POST", "/api/pi/session/start", _m["model" j "platform-fixture"])
            assertEquals(202, start.status); val id = body(start)["sessionId"] as String
            awaitState(wire, id, "ready")
            assertEquals(202, call(wire, "POST", "/api/pi/session/prompt", _m["sessionId" j id, "text" j "Remember this prompt"]).status)
            started.await()
            assertEquals(202, call(wire, "POST", "/api/pi/session/close", _m["sessionId" j id]).status)
            assertEquals("closing", status(wire, id)["state"]); assertFalse(closed)
            assertEquals(409, call(wire, "POST", "/api/pi/session/prompt", _m["sessionId" j id, "text" j "too late"]).status)
            release.complete(Unit); awaitState(wire, id, "closed"); assertTrue(closed)
            assertEquals(200, call(wire, "POST", "/api/pi/session/close", _m["sessionId" j id]).status)
            assertEquals(404, call(wire, "GET", "/api/pi/session/status?sessionId=$id").status)
            val retained = contexts.transact(id, create = false) { it.state() }
            assertTrue(retained.contains("pi-input-1")); assertTrue(retained.contains("pi-turn-1"))
            assertTrue(retained.contains("Pi answer")); assertTrue(retained.contains("tool evidence"))
        } finally { release.complete(Unit); wire.drain() }
    }

    @Test fun wrapperFailureDoesNotPublishTerminalOrCloseChildBeforeAdmittedGenerationSettles() = runBlocking {
        val contexts = BreadcrumbElement(); val admitted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var closed = false
        val wire = PiWire(this, EmptyCoroutineContext, contexts, { catalog() }, ContentId.of(token.encodeToByteArray()).hex,
            openPort = { _, generation -> object : PiWire.Port {
                override fun prompt(text: String): Map<String, Any?> {
                    this@runBlocking.launch { generation.execute { admitted.complete(Unit); release.await() } }
                    runBlocking { admitted.await() }
                    error("wrapper failed while admitted work remains")
                }
                override fun close() { check(release.isCompleted); closed = true }
            } })
        try {
            val id = body(call(wire, "POST", "/api/pi/session/start", _m["model" j "platform-fixture"]))["sessionId"] as String
            awaitState(wire, id, "ready")
            call(wire, "POST", "/api/pi/session/prompt", _m["sessionId" j id, "text" j "orphan probe"])
            admitted.await(); awaitState(wire, id, "closing"); assertFalse(closed)
            release.complete(Unit); awaitState(wire, id, "failed"); assertTrue(closed)
            assertEquals(200, call(wire, "POST", "/api/pi/session/close", _m["sessionId" j id]).status)
            contexts.transact(id, create = false) { assertTrue(it.state().contains("pi-input-1")) }
        } finally { release.complete(Unit); wire.drain() }
    }

    @Test fun twoTurnsRetainOnlyNewTranscriptMessages() = runBlocking {
    val contexts = BreadcrumbElement()
    val first = listOf(
        mapOf("role" to "user", "content" to "first question"),
        mapOf("role" to "assistant", "content" to "first answer")
    )
    val second = listOf(
        mapOf("role" to "user", "content" to "second question"),
        mapOf("role" to "assistant", "content" to "second answer")
    )
    var calls = 0
    val wire = PiWire(this, EmptyCoroutineContext, contexts, { catalog() }, ContentId.of(token.encodeToByteArray()).hex,
        openPort = { _, _ -> object : PiWire.Port {
            override fun prompt(text: String): Map<String, Any?> {
                calls++
                assertEquals(if (calls == 1) "first question" else "second question", text)
                return mapOf("messages" to if (calls == 1) first else first + second, "buffered" to true)
            }
            override fun close() = Unit
        } })
    try {
        val start = call(wire, "POST", "/api/pi/session/start", _m["model" j "platform-fixture"])
        assertEquals(202, start.status)
        val id = body(start)["sessionId"] as String
        awaitState(wire, id, "ready")
        assertEquals(202, call(wire, "POST", "/api/pi/session/prompt", _m["sessionId" j id, "text" j "first question"]).status)
        awaitState(wire, id, "ready")
        assertEquals(true, status(wire, id)["latestTurnRetained"])
        assertEquals(202, call(wire, "POST", "/api/pi/session/prompt", _m["sessionId" j id, "text" j "second question"]).status)
        awaitState(wire, id, "ready")
        assertEquals(true, status(wire, id)["latestTurnRetained"])
        assertEquals(2, calls)

        val state = contexts.transact(id, create = false) { reifyStrict(it.state()) as Map<*, *> }
        val records = state["records"] as List<*>
        fun transcript(id: String): Map<*, *> {
            val record = records.map { it as Map<*, *> }.single { it["id"] == id }
            assertEquals("pi-transcript", record["source"])
            return reifyStrict(record["content"] as String) as Map<*, *>
        }
        val turn1 = transcript("pi-turn-1")
        val turn2 = transcript("pi-turn-2")
        assertEquals(0, (turn1["fromMessage"] as Number).toInt())
        assertEquals(2, (turn2["fromMessage"] as Number).toInt())
        assertEquals(first, turn1["messages"])
        assertEquals(second, turn2["messages"])
        assertEquals(true, turn2["buffered"])
    } finally { wire.drain() }
}

    @Test fun failedContextCreationReportsNoRetainedContext() = runBlocking {
        val contexts = BreadcrumbElement(capacity = 1)
        contexts.transact("existing") { it.put(BreadcrumbId("evidence"), "fixture", "preserved evidence") }
        var opened = false
        val wire = PiWire(this, EmptyCoroutineContext, contexts, { catalog() }, ContentId.of(token.encodeToByteArray()).hex,
            openPort = { _, _ -> opened = true; error("context failure must precede port opening") })
        try {
            val start = call(wire, "POST", "/api/pi/session/start", _m["model" j "platform-fixture"])
            assertEquals(202, start.status)
            val id = body(start)["sessionId"] as String
            awaitState(wire, id, "failed")
            val failed = status(wire, id)
            assertEquals(false, failed["contextRetained"])
            assertEquals(false, failed["latestTurnRetained"])
            assertEquals("session_failed", (failed["error"] as Map<*, *>)["code"])
            assertFalse(opened)
            assertFailsWith<BreadcrumbContextNotFoundException> { contexts.transact(id, create = false) { it.state() } }
            assertTrue(contexts.transact("existing", create = false) { it.state() }.contains("preserved evidence"))
            assertEquals(200, call(wire, "POST", "/api/pi/session/close", _m["sessionId" j id]).status)
        } finally { wire.drain() }
    }

    @Test fun transcriptCapacityFailureKeepsPriorEvidenceAndLatestResult() = runBlocking {
        val contexts = BreadcrumbElement()
        val first = mapOf("role" to "assistant", "content" to "prior-answer-marker")
        val second = mapOf("role" to "assistant", "content" to ("latest-answer-marker" + "z".repeat(5_000)))
        val wire = PiWire(this, EmptyCoroutineContext, contexts, { catalog() }, ContentId.of(token.encodeToByteArray()).hex,
            openPort = { _, generation -> object : PiWire.Port {
                var turn = 0
                override fun prompt(text: String): Map<String, Any?> {
                    turn++
                    if (turn == 2) runBlocking {
                        // Leave room for the accepted second input but not its 5,000-character output.
                        contexts.transact(generation.session, create = false) {
                            it.put(BreadcrumbId("fixture-fill"), "fixture", "x".repeat(499_000)); it.state()
                        }
                    }
                    return mapOf("messages" to if (turn == 1) listOf(first) else listOf(first, second), "buffered" to true)
                }
                override fun close() = Unit
            } })
        try {
            val id = body(call(wire, "POST", "/api/pi/session/start", _m["model" j "platform-fixture"]))["sessionId"] as String
            awaitState(wire, id, "ready")
            assertEquals(202, call(wire, "POST", "/api/pi/session/prompt", _m["sessionId" j id, "text" j "first question"]).status)
            awaitState(wire, id, "ready")
            assertEquals(true, status(wire, id)["latestTurnRetained"])
            assertEquals(202, call(wire, "POST", "/api/pi/session/prompt", _m["sessionId" j id, "text" j "second question"]).status)
            awaitState(wire, id, "failed")
            val failed = status(wire, id)
            assertEquals(true, failed["contextRetained"])
            assertEquals(false, failed["latestTurnRetained"])
            assertEquals("transcript_retention_failed", (failed["error"] as Map<*, *>)["code"])
            assertTrue(jsonOf(failed["result"]).contains("latest-answer-marker"))
            val retained = contexts.transact(id, create = false) { it.state() }
            assertTrue(retained.contains("pi-turn-1"))
            assertTrue(retained.contains("prior-answer-marker"))
            assertTrue(retained.contains("pi-input-2"))
            assertFalse(retained.contains("pi-turn-2"))
            assertFalse(retained.contains("latest-answer-marker"))
            assertEquals(200, call(wire, "POST", "/api/pi/session/close", _m["sessionId" j id]).status)
            assertTrue(contexts.transact(id, create = false) { it.state() }.contains("prior-answer-marker"))
        } finally { wire.drain() }
    }

    @Test fun closeFailureQuarantinesSessionAndPreservesEvidence() = runBlocking {
        val contexts = BreadcrumbElement()
        val closeCalls = java.util.concurrent.atomic.AtomicInteger()
        val wire = PiWire(this, EmptyCoroutineContext, contexts, { catalog() },
            ContentId.of(token.encodeToByteArray()).hex,
            openPort = { _, _ -> object : PiWire.Port {
                override fun prompt(text: String): Map<String, Any?> = _m[
                    "messages" j _l[_m["role" j "assistant", "content" j _l[
                        _m["type" j "text", "text" j "retained answer"]]]],
                    "buffered" j true]
                override fun close() {
                    closeCalls.incrementAndGet()
                    error("private close failure")
                }
            } })
        try {
            val start = call(wire, "POST", "/api/pi/session/start", _m["model" j "platform-fixture"])
            assertEquals(202, start.status)
            val id = body(start)["sessionId"] as String
            awaitState(wire, id, "ready")
            assertEquals(202, call(wire, "POST", "/api/pi/session/prompt",
                _m["sessionId" j id, "text" j "retained question"]).status)
            awaitState(wire, id, "ready")
            assertEquals(true, status(wire, id)["latestTurnRetained"])
            assertEquals(202, call(wire, "POST", "/api/pi/session/close", _m["sessionId" j id]).status)
            awaitState(wire, id, "failed")
            assertEquals(1, closeCalls.get())
            val failed = status(wire, id)
            assertEquals(true, failed["contextRetained"])
            assertEquals(true, failed["latestTurnRetained"])
            assertTrue(failed.toString().contains("retained answer"))
            assertFalse(failed.toString().contains("private close failure"))
            val refusal = call(wire, "POST", "/api/pi/session/close", _m["sessionId" j id])
            assertEquals(409, refusal.status)
            assertEquals("session_cleanup_incomplete", body(refusal)["error"])
            assertEquals("failed", status(wire, id)["state"])
            contexts.transact(id, create = false) {
                val state = it.state()
                assertTrue(state.contains("retained question"))
                assertTrue(state.contains("retained answer"))
            }
            val failure = assertFailsWith<IllegalStateException> { wire.drain() }
            assertEquals("Pi cleanup incomplete; session contexts retained", failure.message)
            assertEquals(1, closeCalls.get())
            assertEquals(503, call(wire, "POST", "/api/pi/session/start",
                _m["model" j "platform-fixture"]).status)
            assertEquals("failed", status(wire, id)["state"])
        } finally {
            // A quarantined session intentionally makes drain fail; don't hide an earlier assertion.
            runCatching { wire.drain() }
        }
    }
}
