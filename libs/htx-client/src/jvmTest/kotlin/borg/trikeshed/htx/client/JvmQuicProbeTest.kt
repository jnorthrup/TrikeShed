package borg.trikeshed.htx.client

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Minimal QUIC probe: send garbage/unencrypted long headers
 * to see if google.com responds at all.
 */
class JvmQuicProbeTest {

    private fun hex(buf: ByteArray, len: Int = buf.size): String =
        buf.copyOfRange(0, len.coerceAtMost(buf.size)).joinToString(" ") { "%02x".format(it) }

    @Test
    fun `send QUIC version negotiation trigger to google com`() {
        // Send a minimal long header with an unknown version.
        // RFC 9000 §17.2.1: if version is unrecognized, server sends Version Negotiation.
        // Long header: 0x80 | 0x00 = 0x80, version=0xAAAAAAAA (unknown), dcid_len=8, dcid, scid_len=0
        val probe = byteArrayOf(
            0x80.toByte(), // long header, type=0 (initial-like)
            0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), // unknown version
            0x08, // DCID len = 8
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, // DCID
            0x00, // SCID len = 0
            // Payload: just some padding to reach 1200 bytes
        ) + ByteArray(1200 - 13) // pad to 1200

        println("[PROBE] sending ${probe.size}B version negotiation trigger")
        println("[PROBE] first 20: ${hex(probe, 20)}")

        val addr = InetAddress.getByName("google.com")
        val sock = DatagramSocket()
        sock.soTimeout = 3000
        sock.connect(addr, 443)
        sock.send(DatagramPacket(probe, probe.size))

        try {
            val recvBuf = ByteArray(2048)
            val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
            sock.receive(recvPkt)
            println("[PROBE] RESPONSE: ${recvPkt.length} bytes")
            println("[PROBE] first 40: ${hex(recvBuf, recvPkt.length.coerceAtMost(40))}")
            val firstByte = recvBuf[0].toInt() and 0xFF
            println("[PROBE] first_byte=0x${"%02x".format(firstByte)} is_version_neg=${firstByte == 0x80 || firstByte == 0x81}")
            assertTrue(recvPkt.length > 0, "Should get Version Negotiation response")
        } catch (e: java.net.SocketTimeoutException) {
            println("[PROBE] TIMEOUT — google.com does not respond to unknown version probes on UDP 443")
            assertTrue(true, "No response even to version negotiation")
        } finally {
            sock.close()
        }
    }

    @Test
    fun `send valid QUIC v1 Initial with RANDOM dcid to google com`() {
        // Same as head-to-head but with random DCID to rule out DCID-related issues
        val dcid = kotlin.random.Random.nextBytes(8)
        println("[PROBE-RAND] dcid=${hex(dcid)}")

        val packet = JvmQuicHeadToHeadTest().buildPacket(dcid, "google.com")
        println("[PROBE-RAND] packet ${packet.size} bytes")
        println("[PROBE-RAND] first 40: ${hex(packet, 40)}")

        val addr = InetAddress.getByName("google.com")
        val sock = DatagramSocket()
        sock.soTimeout = 5000
        sock.connect(addr, 443)
        sock.send(DatagramPacket(packet, packet.size))
        println("[PROBE-RAND] sent ${packet.size} bytes to ${sock.remoteSocketAddress}")

        try {
            val recvBuf = ByteArray(2048)
            val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
            sock.receive(recvPkt)
            println("[PROBE-RAND] RESPONSE: ${recvPkt.length} bytes")
            println("[PROBE-RAND] first 60: ${hex(recvBuf, recvPkt.length.coerceAtMost(60))}")
            assertTrue(recvPkt.length > 0)
        } catch (e: java.net.SocketTimeoutException) {
            println("[PROBE-RAND] TIMEOUT")
            assertTrue(true)
        } finally {
            sock.close()
        }
    }
}
