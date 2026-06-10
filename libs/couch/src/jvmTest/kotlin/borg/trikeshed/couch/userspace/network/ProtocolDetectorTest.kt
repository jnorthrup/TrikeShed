package borg.trikeshed.couch.userspace.network

import kotlin.test.*

/**
 * Tests for ProtocolDetector — exercises all 4 protocols (HTTP, TLS, SSH, HTTP2)
 * plus edge cases: empty feed, reset, ambiguous data, partial feed.
 */
class ProtocolDetectorTest {

    // ── HTTP ──────────────────────────────────────────────────────

    @Test
    fun `detect HTTP via GET`() {
        val pd = ProtocolDetector()
        pd.feed("GET /index.html HTTP/1.1\r\n".encodeToByteArray())
        assertEquals(Protocol.HTTP, pd.protocol())
    }

    @Test
    fun `detect HTTP via POST`() {
        val pd = ProtocolDetector()
        pd.feed("POST /api HTTP/1.1\r\n".encodeToByteArray())
        assertEquals(Protocol.HTTP, pd.protocol())
    }

    @Test
    fun `detect HTTP via response status line`() {
        val pd = ProtocolDetector()
        pd.feed("HTTP/1.1 200 OK\r\n".encodeToByteArray())
        assertEquals(Protocol.HTTP, pd.protocol())
    }

    @Test
    fun `detect HTTP via all method prefixes`() {
        for (method in listOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")) {
            val pd = ProtocolDetector()
            pd.feed(("$method / HTTP/1.0\r\n").encodeToByteArray())
            assertEquals(Protocol.HTTP, pd.protocol(), "Failed for method: $method")
        }
    }

    // ── TLS ───────────────────────────────────────────────────────

    @Test
    fun `detect TLS via ClientHello byte`() {
        val pd = ProtocolDetector()
        // TLS ClientHello starts with 0x16 (handshake record)
        pd.feed(byteArrayOf(0x16.toByte(), 0x03, 0x01))
        assertEquals(Protocol.TLS, pd.protocol())
    }

    @Test
    fun `detect TLS even with more data after handshake byte`() {
        val pd = ProtocolDetector()
        // Full 5-byte TLS record header: 0x16 0x03 0x01 0x00 0x10 (length=16)
        pd.feed(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x10))
        assertEquals(Protocol.TLS, pd.protocol())
    }

    @Test
    fun `TLS detection is sticky after first byte`() {
        val pd = ProtocolDetector()
        pd.feed(byteArrayOf(0x16.toByte()))
        assertEquals(Protocol.TLS, pd.protocol())
        // Still TLS even if more bytes arrive
        pd.feed("GET / HTTP/1.1\r\n".encodeToByteArray())
        assertEquals(Protocol.TLS, pd.protocol())
    }

    // ── SSH ───────────────────────────────────────────────────────

    @Test
    fun `detect SSH via banner`() {
        val pd = ProtocolDetector()
        pd.feed("SSH-2.0-OpenSSH_8.9\r\n".encodeToByteArray())
        assertEquals(Protocol.SSH, pd.protocol())
    }

    @Test
    fun `SSH detection requires hyphen after SSH`() {
        val pd = ProtocolDetector()
        // "SSH" without '-' should not match
        pd.feed("SSHX-2.0\r\n".encodeToByteArray())
        assertNull(pd.protocol())
    }

    // ── HTTP/2 ────────────────────────────────────────────────────

    @Test
    fun `detect HTTP2 via connection preface`() {
        val pd = ProtocolDetector()
        pd.feed("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeToByteArray())
        assertEquals(Protocol.HTTP2, pd.protocol())
    }

    @Test
    fun `HTTP2 preface with extra bytes still detected`() {
        val pd = ProtocolDetector()
        val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
        pd.feed((preface + "extra frame data").encodeToByteArray())
        assertEquals(Protocol.HTTP2, pd.protocol())
    }

    @Test
    fun `incomplete HTTP2 preface returns null`() {
        val pd = ProtocolDetector()
        pd.feed("PRI * HTTP/2.".encodeToByteArray())
        assertNull(pd.protocol())
    }

    // ── Edge cases ────────────────────────────────────────────────

    @Test
    fun `empty feed returns null`() {
        val pd = ProtocolDetector()
        assertNull(pd.protocol())
    }

    @Test
    fun `feed empty byte array leaves null`() {
        val pd = ProtocolDetector()
        pd.feed(byteArrayOf())
        assertNull(pd.protocol())
    }

    @Test
    fun `unknown protocol prefix returns null`() {
        val pd = ProtocolDetector()
        pd.feed("XMPP <stream:stream>".encodeToByteArray())
        assertNull(pd.protocol())
    }

    @Test
    fun `partial HTTP method not matched`() {
        val pd = ProtocolDetector()
        pd.feed("GE".encodeToByteArray())
        assertNull(pd.protocol())
        pd.feed("T / HTTP/1.1\r\n".encodeToByteArray())
        assertEquals(Protocol.HTTP, pd.protocol())
    }

    @Test
    fun `reset clears state`() {
        val pd = ProtocolDetector()
        pd.feed("GET / HTTP/1.1\r\n".encodeToByteArray())
        assertEquals(Protocol.HTTP, pd.protocol())
        pd.reset()
        assertNull(pd.protocol())
        pd.feed("SSH-2.0-OpenSSH\r\n".encodeToByteArray())
        assertEquals(Protocol.SSH, pd.protocol())
    }

    @Test
    fun `reset clears TLS state too`() {
        val pd = ProtocolDetector()
        pd.feed(byteArrayOf(0x16.toByte()))
        assertEquals(Protocol.TLS, pd.protocol())
        pd.reset()
        assertNull(pd.protocol())
        pd.feed("GET / HTTP/1.1\r\n".encodeToByteArray())
        assertEquals(Protocol.HTTP, pd.protocol())
    }

    @Test
    fun `all four protocols in sequence with reset`() {
        val pd = ProtocolDetector()

        pd.feed("GET / HTTP/1.1\r\n".encodeToByteArray())
        assertEquals(Protocol.HTTP, pd.protocol())
        pd.reset()

        pd.feed(byteArrayOf(0x16.toByte()))
        assertEquals(Protocol.TLS, pd.protocol())
        pd.reset()

        pd.feed("SSH-2.0-OpenSSH_8.9\r\n".encodeToByteArray())
        assertEquals(Protocol.SSH, pd.protocol())
        pd.reset()

        pd.feed("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeToByteArray())
        assertEquals(Protocol.HTTP2, pd.protocol())
    }
}
