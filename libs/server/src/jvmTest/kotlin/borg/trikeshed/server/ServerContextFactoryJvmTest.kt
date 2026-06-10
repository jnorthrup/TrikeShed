package borg.trikeshed.server

import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.HtxKey
import borg.trikeshed.quic.QuicKey
import borg.trikeshed.sctp.SctpKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServerContextFactoryJvmTest {
    @Test
    fun buildsOpenContextForAllProtocolElementsJvm() = runTest {
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
}
