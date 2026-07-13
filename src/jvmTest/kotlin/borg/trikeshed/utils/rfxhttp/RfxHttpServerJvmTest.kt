package borg.trikeshed.utils.rfxhttp

import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.HtxTarget
import borg.trikeshed.htx.HtxScheme
import borg.trikeshed.htx.HtxTransportProtocol
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.j
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RfxHttpServerJvmTest {

    @Test
    fun testRfxServerGet() = runTest {
        val server = RfxHttpServerJvm()

        val request = HtxRequest(
            target = HtxTarget(HtxScheme.HTTP, HtxTransportProtocol.HTTP, "localhost" j 80, "/gwtRequest"),
            method = HtxMethod.GET,
            headers = htxHeaders()
        )

        val response = server.handleRequest(request)

        assertEquals(200, response.status)
        assertTrue(response.body.asString().contains("running"))
    }

    @Test
    fun testRfxServerPostValidPayload() = runTest {
        val server = RfxHttpServerJvm()

        val request = HtxRequest(
            target = HtxTarget(HtxScheme.HTTP, HtxTransportProtocol.HTTP, "localhost" j 80, "/gwtRequest"),
            method = HtxMethod.POST,
            headers = htxHeaders(),
            body = ByteSeries("{\"operations\":[{\"O\":\"PERSIST\"}]}")
        )

        val response = server.handleRequest(request)

        assertEquals(200, response.status)
        assertTrue(response.body.asString().contains("success"))
        assertEquals(1, server.store.size) // ensure it touched the store
    }
}
