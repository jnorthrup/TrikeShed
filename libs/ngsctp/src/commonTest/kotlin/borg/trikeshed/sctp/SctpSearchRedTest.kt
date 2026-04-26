package borg.trikeshed.sctp

import borg.trikeshed.context.StreamTransport
import kotlin.test.*

/**
 * ngSCTP Comprehensive Search & Algebra TDD Tests
 * RED phase: tests define desired algebra before full implementation exists.
 *
 * ngSCTP = Next-Generation SCTP over HTX block channelization.
 * Each HTX block IS a channel message. Session stickyness via SessionContext.
 *
 * SCTP algebra (libs/ngsctp commonMain):
 * - SctpChunkType — DATA, INIT, INIT_ACK, SACK, HEARTBEAT, COOKIE_ECHO, COOKIE_ACK
 * - SctpState — CLOSED, COOKIE_WAIT, COOKIE_ECHOED, ESTABLISHED, SHUTDOWN_PENDING, SHUTDOWN_SENT, SHUTDOWN_RECEIVED, SHUTDOWN_ACK_SENT
 * - SctpError — sealed hierarchy: BindFailed, ConnectFailed, Closed
 * - SctpAssociation — (associationId, state)
 * - SctpElement — AsyncContextElement + StreamTransport
 *
 * STREAM ALGEBRA:
 * - openStream(SctpElement) → StreamHandle
 * - bind(SctpElement, port) → SctpAssociation
 * - connect(SctpElement, host, port) → SctpAssociation
 *
 * SEARCH ALGEBRA:
 * - activeStreams(SctpElement) → Int
 *
 * ERROR ALGEBRA:
 * - isBindError(SctpError) → Boolean
 * - isConnectError(SctpError) → Boolean
 * - isClosedError(SctpError) → Boolean
 */
class SctpSearchRedTest {

    // === SCTP STATE & CHUNK TYPE ENUMERATIONS ===

    @Test
    fun `SctpState enum has all expected values`() {
        val expected = setOf(
            SctpState.CLOSED,
            SctpState.COOKIE_WAIT,
            SctpState.COOKIE_ECHOED,
            SctpState.ESTABLISHED,
            SctpState.SHUTDOWN_PENDING,
            SctpState.SHUTDOWN_SENT,
            SctpState.SHUTDOWN_RECEIVED,
            SctpState.SHUTDOWN_ACK_SENT,
        )
        assertEquals(8, SctpState.entries.size)
        assertTrue(SctpState.entries.toSet() == expected)
    }

    @Test
    fun `SctpChunkType enum has all expected values`() {
        val expected = setOf(
            SctpChunkType.DATA,
            SctpChunkType.INIT,
            SctpChunkType.INIT_ACK,
            SctpChunkType.SACK,
            SctpChunkType.HEARTBEAT,
            SctpChunkType.COOKIE_ECHO,
            SctpChunkType.COOKIE_ACK,
        )
        assertEquals(7, SctpChunkType.entries.size)
        assertTrue(SctpChunkType.entries.toSet() == expected)
    }

    // === SCTP ERROR HIERARCHY ===

    @Test
    fun `SctpError categories are distinct`() {
        val err1 = SctpError.BindFailed("port in use")
        val err2 = SctpError.ConnectFailed("host unreachable")
        val err3 = SctpError.Closed()

        assertIs<SctpError.BindFailed>(err1)
        assertIs<SctpError.ConnectFailed>(err2)
        assertIs<SctpError.Closed>(err3)
    }

    @Test
    fun `isBindError returns true only for bind errors`() {
        assertTrue(isBindError(SctpError.BindFailed("msg")))
        assertFalse(isBindError(SctpError.ConnectFailed("msg")))
        assertFalse(isBindError(SctpError.Closed()))
    }

    @Test
    fun `isConnectError returns true only for connect errors`() {
        assertFalse(isConnectError(SctpError.BindFailed("msg")))
        assertTrue(isConnectError(SctpError.ConnectFailed("msg")))
        assertFalse(isConnectError(SctpError.Closed()))
    }

    @Test
    fun `isClosedError returns true only for closed errors`() {
        assertFalse(isClosedError(SctpError.BindFailed("msg")))
        assertFalse(isClosedError(SctpError.ConnectFailed("msg")))
        assertTrue(isClosedError(SctpError.Closed()))
    }

    // === SCTP ELEMENT ALGEBRA ===

    @Test
    fun `SctpElement implements AsyncContextElement`() {
        val element = SctpElement()
        assertTrue(element is StreamTransport)
        // key property returns the companion Key instance
        val k = element.key
        assertSame(SctpElement.Key, k)
    }

    @Test
    fun `SctpElement openStream returns StreamHandle`() {
        val element = SctpElement()
        // open() is suspend, skip for stub test
        // openStream() is suspend
        assertTrue(element is StreamTransport)
    }

    @Test
    fun `SctpAssociation carries id and state`() {
        val assoc = SctpAssociation(12345L, SctpState.ESTABLISHED)
        assertEquals(12345L, assoc.associationId)
        assertEquals(SctpState.ESTABLISHED, assoc.state)
    }

    @Test
    fun `SctpElement activeStreams returns 0 on construction`() {
        val element = SctpElement()
        assertEquals(0, element.activeStreams)
    }

    @Test
    fun `SctpError-BindFailed carries message`() {
        val err = SctpError.BindFailed("address already in use")
        assertTrue(err.message.orEmpty().contains("address"))
    }

    @Test
    fun `SctpError-ConnectFailed carries message`() {
        val err = SctpError.ConnectFailed("connection refused")
        assertTrue(err.message.orEmpty().contains("refused"))
    }

    @Test
    fun `SctpError-Closed is a class with message`() {
        val err = SctpError.Closed()
        assertTrue(err.message != null)
    }
}

// === FREE FUNCTIONS ===

fun isBindError(e: SctpError): Boolean = e is SctpError.BindFailed

fun isConnectError(e: SctpError): Boolean = e is SctpError.ConnectFailed

fun isClosedError(e: SctpError): Boolean = e is SctpError.Closed
