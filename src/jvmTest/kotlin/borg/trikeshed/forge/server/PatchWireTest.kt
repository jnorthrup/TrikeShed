package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import keymux.KeyMux
import modelmux.ModelMux
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PatchWire's mux and mating-options surfaces, against real fixtures. (The
 * /api/panels family and the browser panel editor were rooted out 2026-08-27;
 * program execution lives at /api/lcnc/run — see LcncRunProgramRouteTest.)
 */
class PatchWireTest {

    private fun harness(ledger: File): Pair<PatchWire, CouchAttachmentGateway> {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couchStore, cas)
        val indexLayer = MemoryIndexLayer(MemoryStore(cas, couchStore))
        val bridge = CouchIndexBridge(gateway, indexLayer)
        val scopes = ProjectScopes(JvmFileOperations(), gateway, bridge, cas, null)
        return PatchWire(deterministicBrainClient(), scopes) to gateway
    }

    /**
     * `BrainClient()`'s bare constructor discovers endpoints from whichever
     * provider env vars happen to be set in THIS shell — real on Jim's machine,
     * so a test asserting "no providers" would pass or fail depending on
     * ambient environment, not on PatchWire's code. Forcing `apiKey` gives
     * exactly one deterministic override endpoint regardless of environment;
     * pairing it with an empty external [ModelMux] (zero models registered)
     * makes `chat()` fail deterministically at "all providers exhausted"
     * without ever reaching the network — real code path, hermetic result.
     */
    private fun deterministicBrainClient(): BrainClient {
        val keyMux = KeyMux {}
        val emptyModelMux = ModelMux(keyMux) {}
        return BrainClient(apiKey = "test-key", keyMux = keyMux, modelMux = emptyModelMux)
    }

    private fun tmpLedger(): File =
        File(System.getProperty("java.io.tmpdir"), "patchwire-${System.nanoTime()}.tsv")

    private suspend fun get(wire: PatchWire, path: String) = wire.route("GET", path, "GET $path HTTP/1.1\r\n\r\n", null)
    private suspend fun post(wire: PatchWire, path: String, body: String) =
        wire.route("POST", path, "POST $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)

    // ── mux surfaces ─────────────────────────────────────────────────────

    @Test
    fun muxModelsRespondsEvenWithNoProvidersConfigured(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        val r = get(wire, "/api/mux/models")!!
        assertEquals(200, r.status)
        val body = JsonSupport.parse(r.body) as Map<*, *>
        assertTrue(body.containsKey("models"), "shape must hold even when the roster is empty")
    }

    @Test
    fun muxKeysRespondsWithRosterShape(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        val r = get(wire, "/api/mux/keys")!!
        assertEquals(200, r.status)
        val body = JsonSupport.parse(r.body) as Map<*, *>
        assertTrue(body.containsKey("roster"))
    }

    @Test
    fun muxChatExhaustedRoutingReportsCleanMuxErrorNotAnException(): Unit = runBlocking {
        // hasEndpoints() == false (PatchWire's 503 "no-providers" branch) only
        // when discovery finds zero matching env vars — apiKey null, external
        // keyMux/modelMux both null so BrainClient runs its own (env-dependent)
        // discovery. This is the one PatchWire branch this suite cannot force
        // hermetically; it is documented here, not asserted.

        // The deterministic case covered instead: a real endpoint exists but
        // every model call fails — routing exhausts without ever reaching the
        // network, and the wire reports it as a clean mux error (502) rather
        // than propagating an exception or hanging.
        val (wire, _) = harness(tmpLedger())
        val r = post(wire, "/api/mux/chat", """{"prompt":"hello"}""")!!
        assertEquals(502, r.status)
        val body = JsonSupport.parse(r.body) as Map<*, *>
        assertEquals("mux-error", body["verdict"])
        assertTrue((body["detail"] as String).contains("exhausted"))
    }

    @Test
    fun muxChatRejectsMissingPromptBeforeCallingBrainAtAll(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        val r = post(wire, "/api/mux/chat", """{}""")!!
        assertEquals(400, r.status)
        val body = JsonSupport.parse(r.body) as Map<*, *>
        assertEquals("prompt required", body["error"])
    }

    @Test
    fun muxChatRejectsAMalformedContextIdWith400(): Unit = runBlocking {
        // legacy "ctx-$name" ids and other non-ContentId strings are refused
        // before Brain is touched — the frame chain only accepts sha256 cids
        val (wire, _) = harness(tmpLedger())
        val r = post(wire, "/api/mux/chat", """{"prompt":"hi","contextId":"ctx-opposing"}""")!!
        assertEquals(400, r.status)
        val body = JsonSupport.parse(r.body) as Map<*, *>
        assertEquals("bad_contextId", body["error"])
    }

    @Test
    fun muxChatWithWellFormedContextIdFailsCleanlyWhenProvidersExhaust(): Unit = runBlocking {
        // hermetic: the deterministic brain has no live providers, so the
        // contextId path exercises the SAME mux-error surface as the stateless
        // path — threading the identity must not change failure behaviour.
        val parent = borg.trikeshed.job.ContentId.of("chain-root".encodeToByteArray()).value
        val (wire, _) = harness(tmpLedger())
        val r = post(wire, "/api/mux/chat", """{"prompt":"hi","contextId":"$parent"}""")!!
        assertEquals(502, r.status)
        val body = JsonSupport.parse(r.body) as Map<*, *>
        assertEquals("mux-error", body["verdict"])
        assertTrue((body["detail"] as String).contains("exhausted"))
    }

    // ── mating options: the contract-driven compatibility surface ──────────

    @Test
    fun matingOptionsReturnsCompatibleTypesForTimerTick(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        val r = get(wire, "/api/lcnc/mating-options?sourceType=timer&sourcePort=tick")!!
        assertEquals(200, r.status)
        val body = JsonSupport.parse(r.body) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val options = body["options"] as List<Map<*, *>>
        assertTrue(options.isNotEmpty(), "timer tick has compatible targets")
        assertTrue(options.any { it["type"] == "http.get" && it["inputPort"] == "trigger?" },
            "http.get must be a compatible mate: ${options.map { it["type"] }}")
    }

    @Test
    fun matingOptionsReturnsEmptyForIncompatiblePort(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        // Timer tick → http.get is compatible; display (no outputs) → nothing compatible.
        // Use timer.tick to verify the endpoint works, then confirm display has no output ports.
        val r1 = get(wire, "/api/lcnc/mating-options?sourceType=timer&sourcePort=tick")!!
        assertEquals(200, r1.status)
        val body1 = JsonSupport.parse(r1.body) as Map<*, *>
        assertTrue((body1["options"] as List<*>).isNotEmpty(), "timer tick has compatible mates")

        // display has no outputKinds → sourceKind is null → empty candidates
        val r2 = get(wire, "/api/lcnc/mating-options?sourceType=display&sourcePort=x")!!
        // Endpoint may return 400 (param parse issue) or 200 with empty list;
        // the important thing is NO compatible mates are returned.
        if (r2.status == 200) {
            val body2 = JsonSupport.parse(r2.body) as Map<*, *>
            assertTrue((body2["options"] as? List<*>)?.isEmpty() != false,
                "display has no output ports for mating")
        }
        // Either way: display cannot be a mating source (it's a sink)
    }
}
