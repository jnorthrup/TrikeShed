package borg.trikeshed.torrent

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.*

class TorrentUdpTrackerTest {
    @Test fun connectAndAnnounceMatchBep15Offsets() {
        assertContentEquals(byteArrayOf(0, 0, 4, 0x17, 0x27, 0x10, 0x19, 0x80.toByte(),
            0, 0, 0, 0, 0x12, 0x34, 0x56, 0x78), TorrentUdpTracker.connectPacket(0x12345678))
        val hash = ByteArray(20) { it.toByte() }
        val peer = ByteArray(20) { (it + 64).toByte() }
        val wire = TorrentUdpTracker.announcePacket(0x0102030405060708, 0x12345678, hash, peer,
            6881, uploaded = 17, downloaded = 33, left = 65, event = TrackerEvent.STARTED, key = 0x76543210)
        assertEquals(98, wire.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), wire.copyOf(8))
        assertEquals(1, peerReadInt(wire, 8))
        assertEquals(0x12345678, peerReadInt(wire, 12))
        assertContentEquals(hash, wire.copyOfRange(16, 36))
        assertContentEquals(peer, wire.copyOfRange(36, 56))
        assertEquals(33, peerReadInt(wire, 60)); assertEquals(65, peerReadInt(wire, 68)); assertEquals(17, peerReadInt(wire, 76))
        assertEquals(2, peerReadInt(wire, 80)); assertEquals(0, peerReadInt(wire, 84))
        assertEquals(0x76543210, peerReadInt(wire, 88)); assertEquals(64, peerReadInt(wire, 92))
        assertContentEquals(byteArrayOf(0x1a, 0xe1.toByte()), wire.copyOfRange(96, 98))
    }

    @Test fun responseRequiresMatchingTransactionAndBoundedCompactPeers() {
        val header = peerInt32(1) + peerInt32(-123) + peerInt32(1800) + peerInt32(0) + peerInt32(3)
        val peer = byteArrayOf(127, 0, 0, 1, 0x1a, 0xe1.toByte())
        val parsed = TorrentUdpTracker.response(header + peer + peer + byteArrayOf(1, 2, 3, 4, 0, 0), -123)
        assertEquals(1800000L, parsed.intervalMillis)
        assertEquals(1, parsed.peers.size)
        assertEquals(PeerAddress("127.0.0.1", 6881), parsed.peers[0])
        assertFailsWith<IllegalArgumentException> { TorrentUdpTracker.response(header + peer, 123) }
        assertFailsWith<IllegalArgumentException> { TorrentUdpTracker.response(header + peer.copyOf(5), -123) }
        assertFailsWith<IllegalArgumentException> { TorrentUdpTracker.response(header.copyOf(19), -123) }
        assertFailsWith<IllegalArgumentException> { TorrentUdpTracker.response(header + ByteArray(6 * 4097), -123) }
    }

    @Test fun unsupportedEndpointAndAuthenticationFailBeforeSocketCreation() {
        assertEquals(PeerAddress("192.0.2.1", 6969), TorrentUdpTracker.endpoint("udp://192.0.2.1:6969/announce"))
        for (url in listOf("udp://tracker.example:6969/announce", "udp://192.0.2.1/announce",
            "udp://192.0.2.1:0", "udp://192.0.2.256:6969", "udp://192.0.2.1:6969?auth=secret",
            "udp://192.0.2.1:6969/private-token", "udp://user@192.0.2.1:6969/announce")) {
            assertFailsWith<IllegalArgumentException>(url) { TorrentUdpTracker.endpoint(url) }
        }
    }
}
