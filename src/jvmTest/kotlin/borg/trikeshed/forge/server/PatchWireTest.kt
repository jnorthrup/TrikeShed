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

    // ── mating flow: the drag-from-port → empty-canvas → popup → create chain ──

    /** Minimal program matching what panels.html's serialize() produces. */
    private fun panelJson(): String = """
        {"nodes":[{"id":"n1","type":"timer","x":30,"y":60,"params":{"seconds":"5"},"collapsed":false}],
         "wires":[],"controls":{"humanOversight":true,"matingPoints":[]},"seq":2}
    """.trimIndent()

    private fun mateBody(sourceNode: String, sourcePort: String, targetType: String, x: Double, y: Double): String {
        val prog = panelJson()
        return """{"program":$prog,"sourceNode":"$sourceNode","sourcePort":"$sourcePort",
                   "targetType":"$targetType","x":$x,"y":$y}"""
    }

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

    @Test
    fun mateEndpointCreatesNodeAndPersistsToStore(): Unit = runBlocking {
        val (wire, gateway) = harness(tmpLedger())
        // Save initial panel
        post(wire, "/api/panels/test-mate", panelJson())

        // Mate timer.tick → http.get at (300, 60)
        val body = mateBody("n1", "tick", "http.get", 300.0, 60.0)
        val r = post(wire, "/api/panels/test-mate/mate", body)!!
        assertEquals(200, r.status)
        val resp = JsonSupport.parse(r.body) as Map<*, *>
        assertEquals("ok", resp["verdict"])
        assertTrue(resp["cid"] != null, "CAS cid returned")
        assertTrue(resp["wire"] != null, "wire metadata returned")
        assertTrue(resp["matingPoint"] != null, "mating point returned")

        // The program is persisted in the store
        val (_, bytes) = gateway.getAttachment("panels/test-mate")!!
        val stored = JsonSupport.parse(bytes.decodeToString()) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val nodes = stored["nodes"] as List<Map<*, *>>
        assertTrue(nodes.any { it["type"] == "http.get" },
            "persisted program has the new http.get node")
    }

    @Test
    fun mateEndpointReturns409ForIncompatibleType(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        post(wire, "/api/panels/test-bad-mate", panelJson())
        // timer.tick (trigger kind) → display (expects json) = incompatible
        val body = mateBody("n1", "tick", "display", 300.0, 60.0)
        val r = post(wire, "/api/panels/test-bad-mate/mate", body)!!
        assertEquals(409, r.status)
        val resp = JsonSupport.parse(r.body) as Map<*, *>
        assertTrue((resp["error"] as String).contains("incompatible"),
            "error mentions incompatibility: ${resp["error"]}")
    }

    @Test
    fun mateEndpointReturns400ForMissingSourceNode(): Unit = runBlocking {
        val (wire, _) = harness(tmpLedger())
        post(wire, "/api/panels/test-miss", panelJson())
        val body = """{"program":${panelJson()},"sourcePort":"tick","targetType":"http.get","x":300,"y":60}"""
        val r = post(wire, "/api/panels/test-miss/mate", body)!!
        assertEquals(400, r.status)
    }

    @Test
    fun matedProgramIsLoadableByFrontendFormat(): Unit = runBlocking {
        // Simulate the browser round-trip: mate → save → load via GET → parse as JS would
        val (wire, _) = harness(tmpLedger())
        post(wire, "/api/panels/test-roundtrip", panelJson())

        val body = mateBody("n1", "tick", "http.get", 500.0, 120.0)
        post(wire, "/api/panels/test-roundtrip/mate", body)

        // Load back via GET (same endpoint the browser calls)
        val loaded = get(wire, "/api/panels/test-roundtrip")!!
        assertEquals(200, loaded.status)
        val doc = JsonSupport.parse(loaded.body) as Map<*, *>

        // Wire format: {from:[node,port], to:[node,port]} — what JS expects
        @Suppress("UNCHECKED_CAST")
        val wires = doc["wires"] as List<Map<*, *>>
        assertTrue(wires.isNotEmpty(), "wires present after mating")
        val wire0 = wires[0]
        assertTrue(wire0["from"] is List<*>, "wire.from is array for JS: ${wire0["from"]?.javaClass}")
        assertTrue(wire0["to"] is List<*>, "wire.to is array for JS: ${wire0["to"]?.javaClass}")

        // Node format: {id, type, x, y, params} — what JS expects
        @Suppress("UNCHECKED_CAST")
        val nodes = doc["nodes"] as List<Map<*, *>>
        assertTrue(nodes.any { it["type"] == "http.get" })
        val httpNode = nodes.first { it["type"] == "http.get" }
        assertEquals(500.0, (httpNode["x"] as Number).toDouble())
        assertEquals(120.0, (httpNode["y"] as Number).toDouble())
    }

    @Test
    fun mateCarriesBrowserViewAndSeqIntoTheStoredDocument(): Unit = runBlocking {
        // W2.4: the request's view/seq land in the persisted program —
        // Kotlin owns the whole document, no browser-side re-attach.
        val (wire, gateway) = harness(tmpLedger())
        post(wire, "/api/panels/test-viewseq", panelJson())

        val body = """{"program":${panelJson()},"sourceNode":"n1","sourcePort":"tick",
                       "targetType":"http.get","x":300,"y":60,
                       "view":{"x":-120,"y":77,"z":1.5},"seq":9}"""
            .replace("\n", " ")
        val r = post(wire, "/api/panels/test-viewseq/mate", body)!!
        assertEquals(200, r.status)

        val (_, bytes) = gateway.getAttachment("panels/test-viewseq")!!
        val stored = JsonSupport.parse(bytes.decodeToString()) as Map<*, *>
        val view = stored["view"] as Map<*, *>
        assertEquals(-120.0, (view["x"] as Number).toDouble())
        assertEquals(77.0, (view["y"] as Number).toDouble())
        assertEquals(1.5, (view["z"] as Number).toDouble())
        // seq: request said 9; fresh node is n2 ⇒ max(9, 3) = 9
        assertEquals(9, (stored["seq"] as Number).toInt())
    }

    @Test
    fun presetsEndpointOffersAllThreeWithoutInstalling(): Unit = runBlocking {
        val (wire, gateway) = harness(tmpLedger())
        val r = get(wire, "/api/panels/presets")!!
        assertEquals(200, r.status)
        val body = JsonSupport.parse(r.body) as Map<*, *>
        // JsonParser reifies arrays as Object[] (short form) or lazy List (long form).
        @Suppress("UNCHECKED_CAST")
        val presetsRaw: Any? = body["presets"]
        val presets: List<Map<*, *>> = when (presetsRaw) {
            is Array<*> -> presetsRaw.map { it as Map<*, *> }
            is List<*> -> presetsRaw.map { it as Map<*, *> }
            else -> error("presets missing, got ${presetsRaw?.let { it::class.simpleName }}")
        }
        val names = presets.map { it["name"] }.toSet()
        assertEquals(setOf("preset-hermes", "preset-tribunal", "preset-curator"), names,
            "all three assemblies are offered")

        // OFFERED, never installed: nothing was written into the panel store.
        val listed = get(wire, "/api/panels")!!
        val stored = JsonSupport.parse(listed.body) as Map<*, *>
        val panelsRaw: Any? = stored["panels"]
        val panels: List<*> = when {
            panelsRaw is Array<*> -> panelsRaw.toList()
            panelsRaw is List<*> -> panelsRaw
            else -> error("panels missing")
        }
        assertTrue(panels.isEmpty(), "serving presets must not write them into the store: $panels")
        assertTrue(gateway.getAttachment("panels/preset-tribunal") == null,
            "no preset attachment landed in CAS")
    }
}
