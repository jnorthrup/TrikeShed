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

        assertEquals("hello user", result.a)
        assertEquals(10, result.b.a)
        assertEquals(5, result.b.b)

        val req = requireNotNull(recordedRequest)
        assertEquals(HtxMethod.POST, req.method)
        assertTrue(req.headers.toList().any { it.a == "Authorization" && it.b == "Bearer sk-test" })

        htx.close()
    }
}
