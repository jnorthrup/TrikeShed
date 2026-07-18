package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolDetectorTest {
    @Test
    fun testDetectSocks5FromSshBanner() {
        val bytes = "SSH-2.0-OpenSSH_8.9p1".encodeToByteArray()
        val result = ProtocolDetector.detect(bytes, bytes.size)
        assertEquals(Protocol.Socks5, result)
    }

    @Test
    fun testDetectSocks5FromGreeting() {
        val bytes = byteArrayOf(0x05, 0x01, 0x00) // SOCKS5 version 5
        val result = ProtocolDetector.detect(bytes, bytes.size)
        assertEquals(Protocol.Socks5, result)
    }

    @Test
    fun testDetectHttp() {
        val bytes = "GET / HTTP/1.1\r\n".encodeToByteArray()
        val result = ProtocolDetector.detect(bytes, bytes.size)
        assertEquals(Protocol.Http, result)
    }
}
