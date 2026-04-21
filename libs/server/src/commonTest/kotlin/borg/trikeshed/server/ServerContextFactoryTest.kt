package borg.trikeshed.server

import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.HtxKey
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
    fun requestRoundTripReturnsStubResponse() = runTest {
        val context = buildServerContext()
        val htx = context[HtxKey]
        assertNotNull(htx)

        val response = htx.request(method = "GET", path = "/health")
        assertEquals(200, response.status)
        assertEquals("ok", response.body)

        closeServerContext(context)
    }
}
