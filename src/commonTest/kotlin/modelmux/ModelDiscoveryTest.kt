package modelmux

import keymux.KeyMux
import keymux.TestKeySource
import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDiscoveryTest {

    class FakeHtxRouteService(val handler: (HtxRequest) -> HtxResponse) : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val response = handler(request)
            return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
        }
    }

    @Test
    fun parseOpenAiShapedModels() {
        val json = """{"data":[{"id":"gpt-4"},{"id":"gpt-4o-mini"}]}"""
        val models = ModelDiscovery.parseModels("openai", json)
        assertEquals(2, models.size)
        assertEquals("gpt-4", models[0].modelId)
        assertEquals("openai", models[0].provider)
        assertFalse(models[0].freeTier)
    }

    @Test
    fun parseOpenRouterShapedModelsWithContextAndFreePricing() {
        val json = """{"data":[
            {"id":"vendor/big-model","context_length":1000000,"pricing":{"prompt":"0.000001"}},
            {"id":"vendor/free-model","context_length":32000,"pricing":{"prompt":"0"}},
            {"id":"vendor/thing:free","context_length":8000}
        ]}"""
        val models = ModelDiscovery.parseModels("openrouter", json)
        assertEquals(3, models.size)
        assertEquals(1000000, models[0].contextWindow)
        assertFalse(models[0].freeTier)
        assertTrue(models[1].freeTier, "zero prompt price must read as free tier")
        assertTrue(models[2].freeTier, "'free' in the id must read as free tier")
    }

    @Test
    fun parseMalformedPayloadYieldsEmpty() {
        assertEquals(0, ModelDiscovery.parseModels("x", "not json").size)
        assertEquals(0, ModelDiscovery.parseModels("x", """{"nope":1}""").size)
        assertEquals(0, ModelDiscovery.parseModels("x", """{"data":[{"context_length":5}]}""").size)
    }

    @Test
    fun toCatalogBridgesNeutralFactsAndQuota() {
        val models = ModelDiscovery.parseModels(
            "openrouter",
            """{"data":[{"id":"a/free","context_length":100},{"id":"b/paid","context_length":200}]}""",
        )
        val catalog = ModelDiscovery.toCatalog(models) { if (it.freeTier) 42 else 0 }
        assertEquals(2, catalog.size)
        val free = catalog.toList().first { it.model == "a/free" }
        val paid = catalog.toList().first { it.model == "b/paid" }
        assertTrue(free.freeTier)
        assertEquals(42, free.quotaRemaining)
        assertEquals(0, paid.quotaRemaining)
    }

    @Test
    fun discoverSlurpsThroughHtxWithBearerKey() = runTest {
        var recorded: HtxRequest? = null
        val fakeService = FakeHtxRouteService { req ->
            recorded = req
            HtxResponse(status = 200, body = ByteSeries("""{"data":[{"id":"m1"}]}""".encodeToByteArray()))
        }
        val htx = openHtxElement(routeService = fakeService)
        val keyMux = KeyMux { bind("llm.openrouter.key", TestKeySource()) }
        val result = withContext(coroutineContext + htx) {
            ModelDiscovery.discover("openrouter", "https://openrouter.ai/api/v1", keyMux)
        }
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        val req = requireNotNull(recorded)
        assertEquals("/api/v1/models", req.target.requestPath)
        assertTrue(req.headers.toList().any { it.a == "Authorization" && it.b == "Bearer sk-test" })
        htx.close()
    }

    @Test
    fun discoverReportsHttpFailure() = runTest {
        val fakeService = FakeHtxRouteService { _ -> HtxResponse(status = 401, body = ByteSeries("no".encodeToByteArray())) }
        val htx = openHtxElement(routeService = fakeService)
        val result = withContext(coroutineContext + htx) {
            ModelDiscovery.discover("openrouter", "https://openrouter.ai/api/v1", null)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP 401"))
        htx.close()
    }
}
