package borg.trikeshed.agent.keymux

import borg.trikeshed.agent.modelmux.ModelDiscovery
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.*

class LoomDiscoveryTest {
    @Test fun publicCatalogDoesNotReceiveDefaultOrProviderCredentials() = runBlocking {
        val provider = assertNotNull(HarnessRegistry.byId("loom"))
        assertTrue(provider.publicModels)
        assertNull(provider.probeModel)
        var requested = false
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requested = true
                assertEquals("/v1/models", request.target.requestPath)
                assertTrue(request.headers.view.none { it.a.equals("Authorization", ignoreCase = true) })
                val response = HtxResponse(200, body = ByteSeries("""{"data":[{"id":"res-na-a40-48gb/qwen-3.8-27b-735-mtp-q8-0-262144","owned_by":"loom"}]}""".encodeToByteArray()))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        })
        val keys = KeyMux {
            bind("llm.loom.key", FixedKeySource("provider-secret"))
            bind("llm.default.key", FixedKeySource("default-secret"))
        }
        try {
            val models = withContext(htx) {
                ModelDiscovery.discover(provider.id, assertNotNull(provider.defaultBaseUrl), keys, LlmAuth.NONE).getOrThrow()
            }
            assertTrue(requested)
            assertEquals("res-na-a40-48gb/qwen-3.8-27b-735-mtp-q8-0-262144", models[0].modelId)
        } finally { htx.close() }
    }

    @Test fun loomHarnessUsesOperatorEndpointAndKeyOverrides() = runBlocking {
        val env = _m["LOOM_BASE_URL" j "https://loom-fixture.invalid/v1", "LOOM_API_KEY" j "fixture-key"]
        val source = HarnessSource(getenv = { env[it] })
        assertEquals("https://loom-fixture.invalid/v1", source.read("llm.loom.base_url".toKeyPath()))
        assertEquals("fixture-key", source.read("llm.loom.key".toKeyPath()))
        val defaults = HarnessSource(getenv = { null })
        assertEquals("https://api.loom.vsiwest.com/v1", defaults.read("llm.loom.base_url".toKeyPath()))
    }
    @Test fun publicCatalogFollowsPinnedPagesAndRejectsChangedFinalSnapshot() = runBlocking {
        var page = 0
        var changeFinal = false
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                assertTrue(request.headers.view.none { it.a.equals("Authorization", ignoreCase = true) })
                if (page > 0) {
                    assertContains(request.target.requestPath, "after=$page")
                    assertContains(request.target.requestPath, "savepoint=pinned")
                    assertContains(request.target.requestPath, "as_of_ms=10")
                }
                val id = if (page == 0) "na-a40-48gb/qwen-3.8-27b-735-mtp-q8-0-262144"
                    else "res-na-a40-48gb/qwen-3.8-27b-735-mtp-q8-0-262144"
                val point = if (page > 0 && changeFinal) "changed" else "pinned"
                val response = HtxResponse(200, body = ByteSeries(
                    """{"data":[{"id":"$id"}],"savepoint":"$point","as_of_ms":10,"has_more":${page == 0},"after":"1"}""".encodeToByteArray()))
                page++
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        })
        try {
            val models = withContext(htx) { ModelDiscovery.discover("loom", "https://fixture.invalid/v1", auth = LlmAuth.NONE).getOrThrow() }
            assertEquals(2, models.size)
            assertEquals(2, page)
            page = 0; changeFinal = true
            val changed = withContext(htx) { ModelDiscovery.discover("loom", "https://fixture.invalid/v1", auth = LlmAuth.NONE) }
            assertTrue(changed.isFailure)
            assertContains(changed.exceptionOrNull()!!.message!!, "snapshot changed")
        } finally { htx.close() }
    }

}
