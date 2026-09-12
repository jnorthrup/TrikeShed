package borg.trikeshed.torrent

import kotlin.test.*

class PeerWireProtocolTest {
    private val hash = ByteArray(20) { it.toByte() }
    private val peer = ByteArray(20) { (it + 80).toByte() }
    private fun handshake() = PeerHandshake(infoHash = hash, peerId = peer).encode()

    @Test fun unsignedIntegersAndExactWidths() {
        val request = PeerWireMessage.Request(257, 32768, 16384)
        assertEquals(request, PeerWireMessage.decode(request.encode()))
        assertEquals(PeerWireMessage.Have(128), PeerWireMessage.decode(PeerWireMessage.Have(128).encode()))
        assertEquals(PeerWireMessage.Port(65535), PeerWireMessage.decode(PeerWireMessage.Port(65535).encode()))
        assertEquals(193, (PeerWireMessage.decode(PeerWireMessage.Extension(193, byteArrayOf(1)).encode()) as PeerWireMessage.Extension).id)
        assertFailsWith<IllegalArgumentException> { PeerWireMessage.decode(peerInt32(2) + byteArrayOf(0, 1)) }
        assertFailsWith<IllegalArgumentException> { PeerWireMessage.decode(peerInt32(5) + byteArrayOf(6, 0, 0, 0, 1)) }
        assertFailsWith<IllegalArgumentException> { PeerWireMessage.decode(peerInt32(-1)) }
        assertFailsWith<IllegalArgumentException> { PeerWireMessage.Request(0, 0, 16385).encode() }
        assertFailsWith<IllegalArgumentException> { PeerWireMessage.decode(PeerWireMessage.Choke.encode() + PeerWireMessage.Unchoke.encode()) }
    }

    @Test fun allFragmentBoundariesAndCoalescingPreserveFrames() {
        val expected = listOf(PeerWireMessage.KeepAlive, PeerWireMessage.Have(128), PeerWireMessage.Unchoke)
        val wire = handshake() + expected.flatMap { it.encode().toList() }.toByteArray()
        for (cut in 0..wire.size) {
            val framer = PeerWireFramer(hash, peer)
            val got = mutableListOf<PeerWireMessage>()
            framer.feed(wire.copyOfRange(0, cut)) { got += it }
            framer.feed(wire.copyOfRange(cut, wire.size)) { got += it }
            framer.end()
            assertEquals(expected, got, "fragment boundary $cut")
        }
        val framer = PeerWireFramer(hash)
        val got = mutableListOf<PeerWireMessage>()
        for (byte in wire) framer.feed(byteArrayOf(byte)) { got += it }
        assertEquals(expected, got)
        framer.end()
    }

    @Test fun wrongIdentityOversizeAndTruncationFailClosed() {
        assertFailsWith<IllegalArgumentException> { PeerWireFramer(ByteArray(20)).feed(handshake()) {} }
        assertFailsWith<IllegalArgumentException> { PeerWireFramer(hash, ByteArray(20)).feed(handshake()) {} }
        assertFailsWith<IllegalArgumentException> { PeerWireFramer(hash).feed(byteArrayOf(18)) {} }
        assertFailsWith<IllegalArgumentException> {
            PeerWireFramer(hash).feed(handshake() + peerInt32(Int.MAX_VALUE)) {}
        }
        val incomplete = PeerWireFramer(hash)
        incomplete.feed(handshake() + byteArrayOf(0, 0)) {}
        assertFailsWith<IllegalArgumentException> { incomplete.end() }
    }

    @Test fun v2HandshakeAndHashRangeWireUseFullRootAndTruncatedIdentity() {
        val fullHash = InfoHash(ByteArray(32) { it.toByte() })
        val reserved = ByteArray(8).also { it[5] = 0x10 }
        val wire = PeerHandshake(reservedBytes = reserved, infoHash = fullHash.wireBytes, peerId = peer).encode()
        val decoded = assertNotNull(PeerHandshake.decode(wire))
        assertTrue(decoded.extensions)
        assertContentEquals(fullHash.bytes.copyOf(20), decoded.infoHash)
        val range = PeerHashRange(ByteArray(32) { 128.toByte() }, 1, 256, 2, 3)
        val hashes = ByteArray(160) { it.toByte() }
        val message = PeerWireMessage.decode(PeerWireMessage.Hashes(range, hashes).encode()) as PeerWireMessage.Hashes
        assertTrue(range.matches(message.range))
        assertContentEquals(hashes, message.hashes)
        assertFailsWith<IllegalArgumentException> { PeerHashRange(ByteArray(32), 0, 1, 2, 0) }
    }
}
