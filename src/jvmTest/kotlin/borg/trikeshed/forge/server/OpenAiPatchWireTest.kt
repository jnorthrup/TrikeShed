package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.htx.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.agent.keymux.*
import kotlinx.coroutines.test.runTest
import borg.trikeshed.agent.modelmux.ModelMux
import kotlin.test.*

class OpenAiPatchWireTest {
    @Test
    fun registryHostsAreCredentialFreeAndInheritedCatalogRetainsItsCredentialBinding() = runTest {
        val requests = mutableListOf<HtxRequest>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests.add(request)
                val response = HtxResponse(200, ByteSeries(
                    """{"choices":[{"message":{"role":"assistant","content":"answer"}}]}""".encodeToByteArray()))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        })
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(store, cas)
        val scopes = ProjectScopes(JvmFileOperations(), gateway,
            CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))), cas, null)
        val keys = KeyMux { bind("llm.default.key", FixedKeySource("operator-secret")) }
        val catalog = ModelMux(keys) { model("operator", setOf("chat"), baseUrl = "https://operator.invalid/v1") }
        val brain = BrainClient(apiKey = "fixture", keyMux = keys, modelMux = catalog)
        val reactor = MuxReactorElement()
        val files = JvmFileOperations()
        val directory = files.createTempDir("breadcrumb-wire")
        val snapshot = java.io.File(directory, "sessions.json")
        val wire = PatchWire(brain, scopes, attachments = gateway, muxContext = htx + reactor, sessionSnapshot = snapshot)
        try {
            assertEquals(200, wire.route("POST", "/api/mux/endpoints",
                """{"name":"local","model":"fixture","base":"http://127.0.0.1:8000/v1","auth":"none"}""", null)?.status)
            // Stored metadata cannot turn a named process environment variable into an outbound credential.
            assertEquals(200, wire.route("POST", "/api/mux/endpoints",
                """{"name":"foreign","model":"fixture","base":"https://foreign.invalid/v1","envVar":"PATH","auth":"required"}""", null)?.status)
            val listing = wire.route("GET", "/v1/models", "", null)!!
            assertTrue(listing.body.contains("host/local/fixture"))
            assertTrue(listing.body.contains("operator"))
            assertFalse(listing.body.contains("host/foreign/fixture"))
            val catalogListing = wire.route("GET", "/api/mux/catalog", "", null)!!
            val cards = assertNotNull(confixDoc(catalogListing.body).docAt("models")).cellKids
            val hostedIds = assertNotNull(confixDoc(listing.body).docAt("data")).cellKids.view
                .map { it["id"]?.reify() }.toSet()
            assertEquals(hostedIds, cards.view.map { it["model"]?.reify() }.toSet())
            val host = cards.view.single { it["model"]?.reify() == "host/local/fixture" }
            assertEquals("none", host["auth"]?.reify())
            assertEquals(false, host["keyPresent"]?.reify())
            assertNull(host["keyId"]?.reify())
            assertNull(host["envVar"]?.reify())
            val operator = cards.view.single { it["model"]?.reify() == "operator" }
            assertEquals("required", operator["auth"]?.reify())
            assertEquals("llm.default.key", operator["keyId"]?.reify())
            assertEquals(true, operator["keyPresent"]?.reify())
            assertFalse(catalogListing.body.contains("operator-secret"))
            assertEquals(200, wire.route("POST", "/v1/chat/completions",
                """{"model":"host/local/fixture","breadcrumb_context":"hosted-session","messages":[{"role":"user","content":"hello"},{"role":"assistant","tool_calls":[{"id":"read-1","type":"function","function":{"name":"read","arguments":"{}"}}]},{"role":"tool","tool_call_id":"read-1","content":"session evidence"}]}""", null)?.status)
            assertEquals("127.0.0.1", requests[0].target.host)
            assertNull(requests[0].headerValue("Authorization"))
            assertEquals("fixture", confixDoc(requests[0].body.toArray(), Syntax.JSON).value("model"))
            val reopened = PatchWire(brain, scopes, attachments = gateway, muxContext = htx + reactor, sessionSnapshot = snapshot)
            for (view in listOf("submitted", "routing")) {
                val path = "/v1/breadcrumbs?context=hosted-session&view=$view"
                val saved = wire.route("GET", path, "", null)!!
                val restored = reopened.route("GET", path, "", null)!!
                assertEquals(200, restored.status)
                assertEquals(saved.body, restored.body, "The selected context's $view breadcrumbs survive owner recreation")
                assertTrue(restored.body.contains(if (view == "submitted") "session evidence" else "host/local/fixture"))
            }
            assertEquals(200, wire.route("POST", "/v1/chat/completions",
                """{"model":"operator","messages":[{"role":"user","content":"hello"}]}""", null)?.status)
            assertEquals("operator.invalid", requests[1].target.host)
            assertEquals("Bearer operator-secret", requests[1].headerValue("Authorization"))
            // Changing a registry destination must not reuse an answer from the previous host.
            assertEquals(200, wire.route("POST", "/api/mux/endpoints",
                """{"name":"local","model":"fixture","base":"http://127.0.0.1:8001/v1","auth":"none"}""", null)?.status)
            assertEquals(200, wire.route("POST", "/v1/chat/completions",
                """{"model":"host/local/fixture","messages":[{"role":"user","content":"hello"}]}""", null)?.status)
            assertEquals(3, requests.size)
            assertEquals(8001, requests[2].target.port)
            val activity = wire.route("GET", "/api/mux/activity", "", null)!!
            val calls = assertNotNull(confixDoc(activity.body).docAt("calls")).cellKids
            assertEquals(3, calls.size, "Catalog wrappers must retain dispatch history without duplicating their shared journal")
            assertEquals("hosted-session", calls[0]["contextId"]?.reify())
            assertEquals(listOf("host/local/fixture", "operator", "host/local/fixture"), calls.view.map { it["model"]?.reify() })
            assertFalse(activity.body.contains("operator-secret"))
            val disabled = wire.route("POST", "/api/mux/endpoints",
                """{"name":"local","model":"fixture","base":"http://127.0.0.1:8001/v1","auth":"none","flags":{"enabled":false}}""", null)!!
            assertEquals(200, disabled.status)
            assertEquals(false, confixDoc(disabled.body).value("hosted"))
            assertFalse(wire.route("GET", "/v1/models", "", null)!!.body.contains("host/local/fixture"))
            assertFalse(wire.route("GET", "/api/mux/catalog", "", null)!!.body.contains("host/local/fixture"))
            assertEquals(404, wire.route("POST", "/v1/chat/completions",
                """{"model":"host/local/fixture","messages":[{"role":"user","content":"hello"}]}""", null)?.status)
            assertEquals(3, requests.size, "A disabled host cannot dispatch or return cached completions")
        } finally {
            try { reactor.close() } finally {
                try { htx.close() } finally { files.deleteRecursively(directory) }
            }
        }
    }
}
