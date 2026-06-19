package borg.trikeshed.server

import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.quic.QuicKey
import borg.trikeshed.sctp.SctpKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServerContextFactoryTest {
    @Test
    fun buildsOpenContextForAllProtocolElements() = runTest {
        val context = buildServerContext()

        val quic = context[QuicKey]
        val sctp = context[SctpKey]
        val htx = context[HtxKey]

        assertNotNull(quic)
        assertNotNull(sctp)
        assertNotNull(htx)
        assertEquals(ElementState.OPEN, quic.state)
        assertEquals(ElementState.OPEN, sctp.state)
        assertEquals(ElementState.OPEN, htx.state)

        closeServerContext(context)
    }

    @Test
    fun requestRoundTripMatchesTheServerHealthRoute() = runTest {
        val context = buildServerContext()
        val htx = context[HtxKey]
        assertNotNull(htx)

        val health = htx.request(method = "GET", path = "/health")
        assertEquals(200, health.status)
        assertEquals("ok", health.body.asString())

        val wrongMethod = htx.request(method = "POST", path = "/health")
        assertEquals(405, wrongMethod.status)
        assertEquals("method not allowed", wrongMethod.body.asString())

        val missing = htx.request(method = "GET", path = "/healthz")
        assertEquals(404, missing.status)
        assertEquals("not found", missing.body.asString())

        closeServerContext(context)
    }
}
