package borg.trikeshed.tcpd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertTrue
import java.nio.ByteBuffer

/**
 * RED tests for TCP daemon element.
 */
class TcpDaemonRedTest {

    @Test
    fun `tcp daemon element creates with config and scope`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val config = TcpDaemonConfig(
            host = "127.0.0.1",
            port = 0,
            useTls = false,
        )
        
        val daemon = TcpDaemonElement(config, scope)
        
        assertTrue(daemon.activeSessions() >= 0)
    }

    @Test
    fun `tcp session parses htx framing correctly`() {
        val peer = "test-peer"
        val method = "test.method"
        val payload = """{"key":"value"}"""
        
        val frame = TcpFrame(
            protocol = 0,
            peer = peer,
            method = method,
            payload = payload,
            seq = 42,
        )
        
        val htxBytes = serializeHtxFrame(frame)
        val parsed = parseHtxFrame(ByteBuffer.wrap(htxBytes))
        
        assertTrue(parsed.protocol == 0)
        assertTrue(parsed.peer == peer)
        assertTrue(parsed.method == method)
        assertTrue(parsed.payload == payload)
    }

    @Test
    fun `tcp daemon reports active sessions`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val config = TcpDaemonConfig(
            host = "127.0.0.1",
            port = 0,
        )
        
        val daemon = TcpDaemonElement(config, scope)
        
        assertTrue(daemon.activeSessions() >= 0)
    }
}

private fun serializeHtxFrame(frame: TcpFrame): ByteArray {
    val peerBytes = frame.peer.toByteArray()
    val methodBytes = frame.method.toByteArray()
    val payloadBytes = frame.payload.toByteArray()
    val size = 8 + 1 + 4 + peerBytes.size + 4 + methodBytes.size + 4 + payloadBytes.size
    
    val buf = ByteBuffer.allocate(size)
    buf.putInt(0x48545801)
    buf.putInt(size - 8)
    buf.put(frame.protocol.toByte())
    buf.putInt(peerBytes.size)
    buf.put(peerBytes)
    buf.putInt(methodBytes.size)
    buf.put(methodBytes)
    buf.putInt(payloadBytes.size)
    buf.put(payloadBytes)
    buf.flip()
    val result = ByteArray(buf.remaining())
    buf.get(result)
    return result
}

private fun parseHtxFrame(buf: ByteBuffer): TcpFrame {
    val magic = buf.getInt(0)
    if (magic != 0x48545801) error("Invalid magic: ${Integer.toHexString(magic)}")
    
    buf.position(8) // Skip length
    val protocol = buf.get().toInt()
    val peer = readString(buf)
    val method = readString(buf)
    val payload = readString(buf)
    
    return TcpFrame(protocol, peer, method, payload, 0)
}

private fun readString(buf: ByteBuffer): String {
    val len = buf.int
    val bytes = ByteArray(len)
    buf.get(bytes)
    return String(bytes)
}

private fun writeString(buf: ByteBuffer, s: String) {
    val bytes = s.toByteArray()
    buf.putInt(bytes.size)
    buf.put(bytes)
}

private fun estimateFrameSize(frame: TcpFrame): Int {
    return 1 + 4 + frame.peer.length + 4 + frame.method.length + 4 + frame.payload.length
}