package borg.trikeshed.couch.transport

import borg.trikeshed.couch.runtime.CouchRuntime
import borg.trikeshed.couch.runtime.Reactor
import borg.trikeshed.couch.transport.htx.HtxBackedCouchTransport
import borg.trikeshed.couch.transport.htx.HtxCouchExchange
import borg.trikeshed.couch.transport.htx.HtxRequestFactoryBridge
import borg.trikeshed.couch.transport.htx.HtxRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReactorHtxReuseRedTest {
    @Test
    fun runtimeRequiresInjectedReactorAndDoesNotCreateRawSocketTransport() {
        val reactor = Reactor()
        val runtime = CouchRuntime(reactor = reactor)

        assertSame(reactor, runtime.reactor)
        assertTrue(runtime.transport is HtxBackedCouchTransport)
    }

    @Test
    fun transportBuildsViewFetchAsHtxRequest() {
        val reactor = Reactor()
        val transport = HtxBackedCouchTransport(reactor)

        val exchange: HtxCouchExchange = transport.view(
            database = "acmevehicle",
            path = "_design/example/_view/by_brand?key=%22vw%22",
        )

        val request: HtxRequest = exchange.request
        assertEquals("GET", request.method)
        assertEquals("application/json", request.accept)
    }

    @Test
    fun requestFactoryBridgeMapsGwtRequestIntoCouchServiceInvocationPlan() {
        val bridge = HtxRequestFactoryBridge()

        val rawRequest = "POST /gwtRequest HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 2\r\n\r\n{}"

        val plan = bridge.decode(rawRequest)

        assertEquals("/gwtRequest", plan.requestPath)
        assertEquals("application/json", plan.contentType)
        assertTrue(plan.dispatchMode.isRequestFactory)
        assertTrue(plan.allowsRelaxFactoryStyleCouchServices)
    }
}
