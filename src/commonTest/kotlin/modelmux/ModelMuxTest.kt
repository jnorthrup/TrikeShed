package modelmux

import keymux.*
import modelmux.acp.*
import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.coroutines.coroutineContext

class ModelMuxTest {

    class FakeHtxRouteService(val handler: (HtxRequest) -> HtxResponse) : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val response = handler(request)
            return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
        }
    }

    @Test
    fun modelRouting_selectsMatchingCapability() {
        val keyMux = KeyMux {}
        val models = ModelMux(keyMux) {
            model("gpt-4", caps = setOf("chat", "stream", "tools"))
            model("embed-3", caps = setOf("embed"))
        }

        val routeChat = models.route("chat", "tools")
        assertEquals(1, routeChat.a.size)
        assertEquals("gpt-4", routeChat.a[0].a)

        val routeEmbed = models.route("embed")
        assertEquals(1, routeEmbed.a.size)
        assertEquals("embed-3", routeEmbed.a[0].a)
    }

    @Test
    fun chatCompletion_sendsRequestAndParsesResponse() = runTest {
        var recordedRequest: HtxRequest? = null
        val fakeService = FakeHtxRouteService { req ->
            recordedRequest = req
            HtxResponse(
                status = 200,
                body = ByteSeries("""{"choices":[{"message":{"content":"hello user"}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""".encodeToByteArray())
            )
        }

        val htx = openHtxElement(routeService = fakeService)
        val keyMux = KeyMux {
            bind("llm.gpt-4.key", TestKeySource())
        }

        val models = ModelMux(keyMux) {
            model("gpt-4", caps = setOf("chat"))
        }

        val context = coroutineContext + htx
        val result = withContext(context) {
            models.chat("gpt-4", _s["user" j "hi"].toSeries())
        }

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals("hello user", response.a)
        assertEquals(10, response.b.a)
        assertEquals(5, response.b.b)

        val req = requireNotNull(recordedRequest)
        assertEquals(HtxMethod.POST, req.method)
        assertTrue(req.headers.toList().any { it.a == "Authorization" && it.b == "Bearer sk-test" })

        htx.close()
    }

    @Test
    fun chatCompletion_handlesHttpError() = runTest {
        val fakeService = FakeHtxRouteService { _ ->
            HtxResponse(
                status = 500,
                body = ByteSeries("Internal Server Error".encodeToByteArray())
            )
        }

        val htx = openHtxElement(routeService = fakeService)
        val keyMux = KeyMux {
            bind("llm.gpt-4.key", TestKeySource())
        }

        val models = ModelMux(keyMux) {
            model("gpt-4", caps = setOf("chat"))
        }

        val context = coroutineContext + htx
        val result = withContext(context) {
            models.chat("gpt-4", _s["user" j "hi"].toSeries())
        }

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is IllegalStateException)
        assertTrue(exception.message!!.contains("ModelMux chat failed with HTTP 500"))
        htx.close()
    }

    @Test
    fun chatCompletion_handlesNetworkException() = runTest {
        val fakeService = FakeHtxRouteService { _ ->
            throw RuntimeException("Network Timeout")
        }

        val htx = openHtxElement(routeService = fakeService)
        val keyMux = KeyMux {
            bind("llm.gpt-4.key", TestKeySource())
        }

        val models = ModelMux(keyMux) {
            model("gpt-4", caps = setOf("chat"))
        }

        val context = coroutineContext + htx
        val result = withContext(context) {
            models.chat("gpt-4", _s["user" j "hi"].toSeries())
        }

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is RuntimeException)
        assertEquals("Network Timeout", exception.message)
        htx.close()
    }

    @Test
    fun chatCompletion_failsOnMissingModel() = runTest {
        val keyMux = KeyMux {}
        val models = ModelMux(keyMux) {
            model("gpt-4", caps = setOf("chat"))
        }

        val result = models.chat("non-existent-model", _s["user" j "hi"].toSeries())

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is NoSuchElementException)
        assertEquals("Model not found: non-existent-model", exception.message)
    }
}
