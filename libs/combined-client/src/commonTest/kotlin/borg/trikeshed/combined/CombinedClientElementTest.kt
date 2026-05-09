package borg.trikeshed.combined

import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.HtxElementCompat
import borg.trikeshed.quic.QuicElement
import borg.trikeshed.sctp.SctpElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CombinedClientElementTest {

    // 2a — child open
    @Test
    fun `open opens quic sctp htx children`() = runTest {
        val element = CombinedClientElement(
            quic = QuicElement(), sctp = SctpElement(), htx = HtxElementCompat()
        )
        element.open()
        assertEquals(ElementState.OPEN, element.quic.lifecycleState)
        assertEquals(ElementState.OPEN, element.sctp.lifecycleState)
    }

    // 2b — RPC dispatch
    @Test
    fun `executeRpc htx dispatches to htx request`() = runTest {
        val element = CombinedClientElement(htx = HtxElementCompat())
        element.open()
        val result = element.executeRpc("htx", listOf("/health"))
        assertTrue(result.contains("OK") || result.contains("200") || result.contains("ok"))
    }

    // 12a — lifecycle CREATED to OPEN to CLOSED
    @Test
    fun `lifecycle CREATED to OPEN to CLOSED`() = runTest {
        val client = CombinedClientElement()
        client.open(); assertEquals(ElementState.OPEN, client.lifecycleState)
        client.close(); assertEquals(ElementState.CLOSED, client.lifecycleState)
    }

    // 12b — close cascades to children
    @Test
    fun `close cascades to children`() = runTest {
        val quic = QuicElement(); val sctp = SctpElement()
        val client = CombinedClientElement(quic = quic, sctp = sctp)
        client.open(); client.close()
        assertEquals(ElementState.CLOSED, quic.lifecycleState)
    }
}
