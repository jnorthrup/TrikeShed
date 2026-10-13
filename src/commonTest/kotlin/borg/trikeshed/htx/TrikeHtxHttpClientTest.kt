package borg.trikeshed.htx

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.Jev
import borg.trikeshed.parse.reifyMap
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TrikeHtxHttpClientTest {
    @Test fun methodsHeadersAndUtf8BodyUseTheContextRoute() = runTest {
        val requests = mutableListOf<HtxRequest>()
        withHtxClientRoute({ request ->
            requests.add(request)
            HtxResponse(200, ByteSeries("accepted 雪"))
        }) {
            val client = TrikeHtxHttpClient("https://example.test", htxHeaders("Authorization" j "Bearer fixture"))
            val body = """{"text":"雪"}"""
            assertEquals("accepted 雪", client.get("records"))
            assertEquals("accepted 雪", client.post("/records", body))
            assertEquals("accepted 雪", client.delete("records/1"))
            assertEquals(listOf(HtxMethod.GET, HtxMethod.POST, HtxMethod.DELETE), requests.map { it.method })
            assertEquals(listOf("/records", "/records", "/records/1"), requests.map { it.target.requestPath })
            for (request in requests) {
                assertEquals(HtxTransportProtocol.HTTPS, request.target.transportProtocol)
                assertEquals("example.test", request.target.host)
                assertEquals("Bearer fixture", request.headerValue("Authorization"))
                assertEquals("application/json", request.headerValue("Content-Type"))
                assertEquals(request.body.toArray().size.toString(), request.headerValue("Content-Length"))
            }
            assertEquals(body, requests[1].body.toArray().decodeToString())
            assertTrue(requests[0].body.toArray().isEmpty())
            assertTrue(requests[2].body.toArray().isEmpty())
        }
    }

    @Test fun missingContextHttpErrorsAndCancellationAreNotReplaced() = runTest {
        val client = TrikeHtxHttpClient("https://example.test")
        val missing = assertFailsWith<IllegalStateException> { client.get("records") }
        assertTrue(missing.message.orEmpty().contains("HtxKey"))
        withHtxClientRoute({ HtxResponse(429, ByteSeries("retry later")) }) {
            val error = assertFailsWith<HtxHttpError> { client.post("records", "{}") }
            assertEquals(429, error.status)
            assertEquals("retry later", error.body)
        }
        val cancellation = CancellationException("fixture cancellation")
        withHtxClientRoute({ throw cancellation }) {
            val caught = assertFailsWith<CancellationException> { client.get("records") }
            assertEquals(cancellation.message, caught.message)
            // Coroutine stack recovery may copy an exception while retaining its cause.
            assertTrue(generateSequence<Throwable>(caught) { it.cause }.any { it === cancellation })
        }
    }

    @Test fun jevUsesTheSameRouteAndRetriesOnlyItsTransientStatuses() = runTest {
        val requests = mutableListOf<HtxRequest>()
        withHtxClientRoute({ request ->
            requests.add(request)
            when (requests.size) {
                1 -> HtxResponse(429, ByteSeries("rate limit"))
                2 -> HtxResponse(529, ByteSeries("busy"))
                else -> HtxResponse(200, ByteSeries("""{"answers":{"review":{"noul":0.75}}}"""))
            }
        }) {
            val result = Jev.ask("fixture", "synthetic record", mapOf("review" to Jev.noul("review required")))
            assertEquals(0.75, Jev.noulOf(result["answers"] as Map<*, *>, "review"))
            assertEquals(3, requests.size)
            assertEquals(3_000L, testScheduler.currentTime)
            for (request in requests) {
                assertEquals(HtxMethod.POST, request.method)
                assertEquals("api.typesafe.ai", request.target.host)
                assertEquals("/v1/systemone", request.target.requestPath)
                assertEquals("Bearer fixture", request.headerValue("Authorization"))
                val body = reifyMap(request.body.toArray().decodeToString())
                assertEquals(Jev.MODEL, body["model"])
                assertEquals("synthetic record", body["state"])
            }
        }
        var attempts = 0
        withHtxClientRoute({ attempts++; HtxResponse(400, ByteSeries("invalid")) }) {
            assertEquals(400, assertFailsWith<HtxHttpError> { Jev.ask("fixture", "record", emptyMap()) }.status)
            assertEquals(1, attempts)
        }
    }
}

/** Exercise the production client and element without making external requests. */
internal suspend fun <T> withHtxClientRoute(
    response: suspend (HtxRequest) -> HtxResponse,
    block: suspend () -> T,
): T {
    val route = object : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult =
            HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                request = request, response = response(request)))
    }
    val htx = HtxElement(routeService = route, parentJob = currentCoroutineContext()[Job])
    htx.open()
    return try { withContext(htx) { block() } } finally { htx.drain() }
}
