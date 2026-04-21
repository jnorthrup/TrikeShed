package borg.trikeshed.couch.transport

import borg.trikeshed.couch.runtime.CouchRuntime
import borg.trikeshed.couch.transport.htx.HtxBackedCouchTransport
import borg.trikeshed.couch.transport.htx.HtxCouchExchange
import borg.trikeshed.couch.transport.htx.HtxRequestFactoryBridge
import borg.trikeshed.userspace.htx.HtxMessage
import borg.trikeshed.userspace.nio.Reactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReactorHtxReuseRedTest {
    @Test
    fun runtimeRequiresInjectedReactorAndDoesNotCreateRawSocketTransport() {
        val reactor = Reactor(backend = TODO("test backend"))
        val runtime = CouchRuntime(reactor = reactor)

        assertSame(reactor, runtime.reactor)
        assertTrue(runtime.transport is HtxBackedCouchTransport)
    }

    @Test
    fun transportBuildsViewFetchAsHtxRequestMessage() {
        val reactor = Reactor(backend = TODO("test backend"))
        val transport = HtxBackedCouchTransport(reactor)

        val exchange: HtxCouchExchange = transport.view(
            database = "acmevehicle",
            path = "_design/example/_view/by_brand?key=%22vw%22",
        )

        val request = exchange.request
        assertEquals("GET", request.methodName)
        assertEquals("application/json", request.headerValue("Accept"))
    }

    @Test
    fun requestFactoryBridgeMapsGwtRequestIntoCouchServiceInvocationPlan() {
        val bridge = HtxRequestFactoryBridge()
        val request = HtxMessage.parseHttp1(
            (
                "POST /gwtRequest HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: 2\r\n\r\n{}"
                ).encodeToByteArray(),
        )!!

        val plan = bridge.decode(request)

        assertEquals("/gwtRequest", plan.requestPath)
        assertEquals("application/json", plan.contentType)
        assertTrue(plan.dispatchMode.isRequestFactory)
        assertTrue(plan.allowsRelaxFactoryStyleCouchServices)
    }
}
