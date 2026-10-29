package borg.trikeshed.relaxfactory

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import com.google.web.bindery.autobean.shared.AutoBeanCodex
import com.google.web.bindery.requestfactory.shared.RequestTransport
import com.google.web.bindery.requestfactory.shared.Receiver
import com.google.web.bindery.requestfactory.shared.ServerFailure
import com.google.web.bindery.requestfactory.shared.messages.ResponseMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HtxRequestTransportTest {
    @Test
    fun nativeRfCallbacksKeepPayloadAndHttpFailureIdentityWithoutLaunchingPerSend() = runTest {
        val requests = mutableListOf<HtxRequest>()
        val reply = """{ "F": {"M":"native RF protocol failure","F":true} }"""
        val route = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests += request
                val response = if (requests.size == 1) HtxResponse(200, ByteSeries(reply), htxHeaders("Content-Type" j "application/json"))
                    else HtxResponse(503, ByteSeries("processor unavailable"))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        }
        val client = HtxClientReactorElement(route, options = HtxClientOptions(maxRedirects = 0),
            workerContext = coroutineContext.minusKey(Job), parentJob = coroutineContext[Job])
        val element = RequestFactoryClientElement("http://example.test/gwtRequest", client)
        element.open()
        val transport = HtxRequestTransport(element)
        val payload = """{ "F": "factory", "I": [] }"""
        val succeeded = CompletableDeferred<String>()
        val failed = CompletableDeferred<ServerFailure>()
        var callbacks = 0
        val callbackFailure = IllegalStateException("application receiver failure")
        try {
            transport.send(payload, object : RequestTransport.TransportReceiver {
                override fun onTransportSuccess(payload: String) {
                    callbacks++
                    succeeded.complete(payload)
                    throw callbackFailure
                }
                override fun onTransportFailure(failure: ServerFailure) {
                    callbacks++
                    succeeded.completeExceptionally(IllegalStateException(failure.message))
                }
            })
            assertEquals(reply, succeeded.await())
            transport.send(payload, object : RequestTransport.TransportReceiver {
                override fun onTransportSuccess(payload: String) {
                    callbacks++
                    failed.completeExceptionally(IllegalStateException("unexpected success"))
                }
                override fun onTransportFailure(failure: ServerFailure) {
                    callbacks++
                    failed.complete(failure)
                }
            })
            val failure = failed.await()
            assertEquals(HtxHttpError::class.java.name, failure.exceptionType)
            assertTrue(failure.message.contains("503"))
            assertTrue(failure.isFatal)
            assertEquals(2, callbacks)
            assertSame(callbackFailure, element.callbackFailure)
            assertEquals(List(2) { payload }, requests.map { it.body.toArray().decodeToString() })
        } finally {
            withContext(NonCancellable) { element.drain(); client.close() }
        }
    }

    @Test
    fun malformedHttp200SettlesTheActualNativeClientAndFollowingRequestsStillComplete() = runTest {
        val invalid = listOf(
            "not json",
            "{}",
            """{"X":[]}""",
            """{"S":[],"I":[]}""",
            """{"S":[null],"I":[null]}""",
            """{"S":[true,true],"I":[null]}""",
            """{"S":[true],"I":[]}""",
        )
        val cas = CasStore.inMemory()
        val processor = GwtCouchRequestFactory(Couch("rf", CouchStoreFactory.casBacked(cas), cas))
        var requests = 0
        val route = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val body = invalid.getOrNull(requests++) ?: processor.process(request.body.toArray().decodeToString())
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request,
                    response = HtxResponse(200, ByteSeries(body), htxHeaders("Content-Type" j "application/json"))))
            }
        }
        val http = HtxClientReactorElement(route, options = HtxClientOptions(maxRedirects = 0),
            workerContext = coroutineContext.minusKey(Job), parentJob = coroutineContext[Job])
        val client = HtxRequestFactoryClient.open("http://example.test/gwtRequest", http)
        try {
            for (body in invalid) {
                val failure = assertFailsWith<IllegalStateException>("Native RF client hung or accepted malformed response: $body") {
                    withTimeout(5_000) { client.await(client.factory.couch().get("missing")) }
                }
                assertFalse(failure is CancellationException, "Native RF client timed out for malformed response: $body")
                assertTrue(failure.message.orEmpty().isNotBlank())
            }
            assertNull(withTimeout(5_000) { client.await(client.factory.couch().get("missing")) })
            assertEquals(invalid.size + 1, requests)
            assertNull(client.transport.element.callbackFailure)
        } finally {
            withContext(NonCancellable) { client.close(); http.close(); processor.close() }
        }
    }

    @Test
    fun nativeContextWithoutInvocationsAcceptsTheOriginalServersAbsentStatusAndResultArrays() = runTest {
        val cas = CasStore.inMemory()
        val processor = GwtCouchRequestFactory(Couch("rf", CouchStoreFactory.casBacked(cas), cas))
        var response: String? = null
        val route = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val body = processor.process(request.body.toArray().decodeToString())
                response = body
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request,
                    response = HtxResponse(200, ByteSeries(body), htxHeaders("Content-Type" j "application/json"))))
            }
        }
        val http = HtxClientReactorElement(route, options = HtxClientOptions(maxRedirects = 0),
            workerContext = coroutineContext.minusKey(Job), parentJob = coroutineContext[Job])
        val client = HtxRequestFactoryClient.open("http://example.test/gwtRequest", http)
        val completion = CompletableDeferred<Unit>()
        var callbacks = 0
        try {
            client.factory.couch().fire(object : Receiver<Void>() {
                override fun onSuccess(response: Void?) { callbacks++; completion.complete(Unit) }
                override fun onFailure(error: ServerFailure) {
                    callbacks++
                    completion.completeExceptionally(IllegalStateException(error.message))
                }
            })
            withTimeout(5_000) { completion.await() }
            val message = AutoBeanCodex.decode(
                HtxRequestTransport.messages, ResponseMessage::class.java,
                assertNotNull(response),
            ).`as`()
            assertNull(message.statusCodes)
            assertNull(message.invocationResults)
            assertEquals(1, callbacks)
            assertNull(client.transport.element.callbackFailure)
        } finally {
            withContext(NonCancellable) { client.close(); http.close(); processor.close() }
        }
    }
}
