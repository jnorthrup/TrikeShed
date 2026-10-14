package borg.trikeshed.relaxfactory

import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RequestFactoryClientElementTest {
    private suspend fun <T> withClient(
        response: suspend (HtxRequest) -> HtxResponse,
        block: suspend (HtxClientReactorElement) -> T,
    ): T {
        val context = currentCoroutineContext()
        val route = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult =
                HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response(request)))
        }
        val client = HtxClientReactorElement(route, options = HtxClientOptions(maxRedirects = 0),
            workerContext = context.minusKey(Job), parentJob = context[Job]).also { it.open() }
        return try { block(client) } finally { withContext(NonCancellable) { client.close() } }
    }

    private fun json(body: String) = HtxResponse(200, ByteSeries(body), htxHeaders("Content-Type" j "application/json; charset=utf-8"))

    @Test
    fun originalPayloadAndProtocolFailureResponsePassThroughUnchanged() = runTest {
        val requests = mutableListOf<HtxRequest>()
        val payload = """{ "F": "factory", "I": [], "O": [], "text":"café 雪" }"""
        val reply = """{ "E": {"M":"RF owns this failure"} }"""
        withClient({ request -> requests += request; json(reply) }) { client ->
            val element = RequestFactoryClientElement("https://example.test/db/_requestfactory?wire=1", client)
            element.open()
            try {
                withContext(element) { assertSame(element, currentCoroutineContext()[RequestFactoryClientElement]) }
                assertEquals(reply, element.exchange(payload))
                val request = requests.single()
                assertEquals(HtxMethod.POST, request.method)
                assertEquals("/db/_requestfactory?wire=1", request.target.requestPath)
                assertContentEquals(payload.encodeToByteArray(), request.body.toArray())
                assertEquals("application/json; charset=utf-8", request.headerValue("Content-Type"))
                assertEquals("application/json", request.headerValue("Accept"))
            } finally { element.drain() }
            assertEquals(ElementState.ACTIVE, client.state)
        }
    }

    @Test
    fun boundedRefusalsAndThrowingCallbacksStillSettleExactlyOnceBeforeDrain() = runTest {
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
        val results = mutableMapOf<String, Result<String>>()
        val callbacks = mutableMapOf<String, Int>()
        val callbackFailure = IllegalArgumentException("receiver failure")
        withClient({ request ->
            val payload = request.body.toArray().decodeToString()
            requests += payload
            if (payload == "one") release.await()
            json("reply:$payload")
        }) { client ->
            val element = RequestFactoryClientElement("http://example.test/gwtRequest", client, capacity = 1)
            element.open()
            fun receipt(name: String): RequestFactoryClientReceipt = { result ->
                callbacks[name] = (callbacks[name] ?: 0) + 1
                results[name] = result
                if (name == "one") throw callbackFailure
            }
            element.send("one", receipt("one"))
            runCurrent()
            element.send("two", receipt("two"))
            element.send("full", receipt("full"))
            assertIs<IllegalStateException>(results["full"]?.exceptionOrNull())
            val drain = async { element.drain() }
            runCurrent()
            element.send("late", receipt("late"))
            assertIs<IllegalStateException>(results["late"]?.exceptionOrNull())
            assertFalse(drain.isCompleted)
            release.complete(Unit)
            drain.await()
            assertEquals(listOf("one", "two"), requests)
            assertEquals("reply:one", results["one"]?.getOrThrow())
            assertEquals("reply:two", results["two"]?.getOrThrow())
            assertEquals(mapOf("one" to 1, "two" to 1, "full" to 1, "late" to 1), callbacks)
            assertSame(callbackFailure, element.callbackFailure)
            assertEquals(ElementState.CLOSED, element.state)
            assertEquals(ElementState.ACTIVE, client.state)
        }
    }

    @Test
    fun ownerCancellationSettlesAcceptedRequestsAndRejectsFurtherAdmission() = runTest {
        val owner = Job(currentCoroutineContext()[Job])
        val blocked = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
        val results = mutableMapOf<String, Result<String>>()
        withClient({ request ->
            requests += request.body.toArray().decodeToString()
            blocked.await()
            json("unreachable")
        }) { client ->
            val element = RequestFactoryClientElement("http://example.test/gwtRequest", client, parentJob = owner, capacity = 1)
            element.open()
            element.send("one") { results["one"] = it }
            runCurrent()
            element.send("two") { results["two"] = it }
            owner.cancel()
            element.send("late") { results["late"] = it }
            advanceUntilIdle()
            for (name in listOf("one", "two", "late")) assertIs<CancellationException>(results[name]?.exceptionOrNull())
            assertEquals(listOf("one"), requests)
            element.drain()
            assertEquals(ElementState.CLOSED, element.state)
        }
    }

    @Test
    fun httpAndCodecFailuresDoNotMasqueradeAsProtocolResponsesOrPoisonTheQueue() = runTest {
        var request = 0
        val reply = """{"E":{"M":"original protocol failure"}}"""
        withClient({
            when (++request) {
                1 -> HtxResponse(429, ByteSeries("retry later"))
                2 -> HtxResponse(200, ByteSeries("<html/>"), htxHeaders("Content-Type" j "text/html"))
                3 -> HtxResponse(200, ByteSeries(byteArrayOf(0xff.toByte())), htxHeaders("Content-Type" j "application/json"))
                else -> json(reply)
            }
        }) { client ->
            val element = RequestFactoryClientElement("http://example.test/gwtRequest", client)
            element.open()
            try {
                val status = assertFailsWith<HtxHttpError> { element.exchange("status") }
                assertEquals(429, status.status)
                assertEquals("retry later", status.body)
                assertFailsWith<IllegalArgumentException> { element.exchange("content-type") }
                assertTrue(runCatching { element.exchange("utf8") }.isFailure)
                assertEquals(reply, element.exchange("protocol"))
                assertEquals(4, request)
            } finally { element.drain() }
        }
    }
}
