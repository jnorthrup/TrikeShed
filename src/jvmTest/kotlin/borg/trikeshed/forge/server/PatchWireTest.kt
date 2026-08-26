package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.jules.BrainClient
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PatchWire is the LCNC panel graph's real backend: `/panels` (GraalWire) serves
 * the ComfyUI-style editor page, PatchWire serves what its nodes call. This
 * covers exactly the claim panels.html's own comment makes — "constructions are
 * STORE DOCUMENTS (panels/<name>, CAS-addressed, replicated) — not browser
 * state" — against the REAL Oroboros storage plane (CouchAttachmentGateway over
 * an in-memory CasStore/CouchStore, the same fixture [ProjectDbMountTest] uses),
 * not a mock. localStorage is the browser's unsaved-scratch convenience only;
 * this is the actual persistence path the goal (kanban+mux LCNC graphs living in
 * the Oroboros storage db, not the PWA pipeline) depends on.
 */
class PatchWireTest {

    private fun harness(ledger: File): Pair<PatchWire, CouchAttachmentGateway> {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couchStore, cas)
        val indexLayer = MemoryIndexLayer(MemoryStore(cas, couchStore))
        val bridge = CouchIndexBridge(gateway, indexLayer)
        val scopes = ProjectScopes(JvmFileOperations(), gateway, bridge, cas, null)
        return PatchWire(deterministicBrainClient(), scopes, attachments = gateway) to gateway
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

    // ── panel constructions as Oroboros store documents ─────────────────

    @Test
    fun panelSavesToRealCasNotJustAnHttpAcknowledgement(): Unit = runBlocking {
        val (wire, gateway) = harness(tmpLedger())
        val graph = """{"nodes":[{"id":1,"type":"timer"}],"wires":[],"seq":2}"""

        val saved = post(wire, "/api/panels/production-ops", graph)!!
        assertEquals(200, saved.status)
        val savedBody = JsonSupport.parse(saved.body) as Map<*, *>
        assertEquals("ok", savedBody["verdict"])
        assertEquals("production-ops", savedBody["name"])

        // The proof: the bytes are reachable through the gateway's OWN read path
        // (CAS-backed), independent of PatchWire — this is real storage, not an
        // echo. getAttachment/putAttachment round-trip is exactly what
        // CouchAttachmentGatewayTest exercises for arbitrary documents; a panel
        // graph is just another document on that same plane.
        val (ref, bytes) = gateway.getAttachment("panels/production-ops")!!
        assertEquals(graph, bytes.decodeToString())
        assertEquals(savedBody["cid"], ref.contentId.value)
    }

    @Test
    fun panelLoadsBackByteIdenticalThroughTheHttpRoute(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        val graph = """{"nodes":[],"wires":[],"seq":1}"""
        post(wire, "/api/panels/scratch", graph)

        val loaded = get(wire, "/api/panels/scratch")!!
        assertEquals(200, loaded.status)
        assertEquals(graph, loaded.body)
    }

    @Test
    fun panelListEnumeratesSavedGraphsUnderThePanelsPrefix(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        post(wire, "/api/panels/alpha", """{"nodes":[],"wires":[]}""")
        post(wire, "/api/panels/beta", """{"nodes":[],"wires":[]}""")

        val listed = get(wire, "/api/panels")!!
        val names = ((JsonSupport.parse(listed.body) as Map<*, *>)["panels"] as List<*>)
            .map { (it as Map<*, *>)["name"] }
        assertEquals(setOf("alpha", "beta"), names.toSet())
    }

    @Test
    fun panelNameIsValidatedBeforeTouchingStorage(): Unit = runBlocking {
        val (wire, gateway) = harness(tmpLedger())
        val r = post(wire, "/api/panels/../escape", """{"nodes":[]}""")!!
        assertEquals(400, r.status)
        assertNull(gateway.getAttachment("panels/../escape"), "a rejected name must never reach the store")
    }

    @Test
    fun loadingAnUnsavedPanelIsA404NotAnException(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        val r = get(wire, "/api/panels/never-saved")!!
        assertEquals(404, r.status)
    }

    @Test
    fun withoutAStoreEveryPanelsRouteDegradesTo503(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couchStore, cas)
        val indexLayer = MemoryIndexLayer(MemoryStore(cas, couchStore))
        val bridge = CouchIndexBridge(gateway, indexLayer)
        val scopes = ProjectScopes(JvmFileOperations(), gateway, bridge, cas, null)
        val wire = PatchWire(BrainClient(), scopes, attachments = null)

        assertEquals(503, get(wire, "/api/panels")!!.status)
        assertEquals(503, post(wire, "/api/panels/x", "{}")!!.status)
    }
}
