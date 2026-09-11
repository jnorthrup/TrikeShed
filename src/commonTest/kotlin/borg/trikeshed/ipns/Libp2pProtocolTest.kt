package borg.trikeshed.ipns

import borg.trikeshed.runBlocking
import kotlin.test.*

class Libp2pProtocolTest {
    private class Bytes(var input: ByteArray, private val fragment: Int = 1) : Libp2pStream {
        var output = byteArrayOf()
        var closed = false
        override suspend fun read(maxBytes: Int): ByteArray {
            val count = minOf(maxBytes, fragment, input.size)
            return input.copyOfRange(0, count).also { input = input.copyOfRange(count, input.size) }
        }
        override suspend fun write(bytes: ByteArray) { output += bytes }
        override suspend fun close() { closed = true }
    }

    @Test fun multistream_fragmentation_and_rejection() = runBlocking<Unit> {
        val messages = Libp2pMultistream.frame(Libp2pMultistream.HEADER) + Libp2pMultistream.frame("/ipfs/kad/1.0.0")
        val wire = Bytes(messages)
        Libp2pMultistream.select(wire, "/ipfs/kad/1.0.0")
        assertContentEquals(messages, wire.output)
        assertFailsWith<IllegalStateException> {
            Libp2pMultistream.select(Bytes(Libp2pMultistream.frame(Libp2pMultistream.HEADER) + Libp2pMultistream.frame("na")), "/ipfs/kad/1.0.0")
        }
        assertFailsWith<IllegalStateException> { Libp2pMultistream.receive(Bytes(byteArrayOf(4, 65))) }
        assertFailsWith<IllegalArgumentException> { Libp2pMultistream.receive(Bytes(byteArrayOf(-1, -1, 1))) }
    }

    @Test fun numeric_multiaddr_does_not_accept_mismatched_peer_or_other_transport() {
        val peer = IpnsName.fromPublicKey(ByteArray(32)).multihash
        val tcp = byteArrayOf(4, 127, 0, 0, 1, 6, 0x10, 0x01)
        val address = Libp2pTcpAddress.decode(tcp, peer)
        assertEquals("127.0.0.1", address.host)
        assertEquals(4097, address.port)
        assertContentEquals(byteArrayOf(2, 0, 0x10, 1, 127, 0, 0, 1) + ByteArray(8), address.sockaddr)
        val other = IpnsName.fromPublicKey(ByteArray(32) { 1 }).multihash
        assertFailsWith<IllegalArgumentException> {
            Libp2pTcpAddress.decode(tcp + IpnsEncoding.varint(421u) + IpnsEncoding.varint(other.size.toULong()) + other, peer)
        }
        assertFailsWith<IllegalArgumentException> { Libp2pTcpAddress.decode(tcp.copyOf().also { it[5] = 17 }, peer) }
    }

    @Test fun yamux_consumption_replenishes_window_and_reset_is_terminal() = runBlocking<Unit> {
        // ACK of stream1, then fragmented payload with FIN: bytes must remain readable before EOF.
        val ack = byteArrayOf(0, 1, 0, 2, 0, 0, 0, 1, 0, 0, 0, 0)
        val data = byteArrayOf(0, 0, 0, 4, 0, 0, 0, 1, 0, 0, 0, 3, 9, 8, 7)
        val wire = Bytes(ack + data)
        val stream = Libp2pYamuxStream(wire, 1000)
        stream.write(byteArrayOf(1))
        assertEquals(1, wire.output[3].toInt()) // initial SYN
        assertContentEquals(byteArrayOf(9, 8), stream.read(2))
        assertContentEquals(byteArrayOf(7), stream.read(2))
        assertTrue(stream.read(2).isEmpty())
        assertEquals(1, wire.output[14].toInt()) // window update type after 13-byte initial frame
        stream.close()
        assertTrue(wire.closed)
        val reset = Libp2pYamuxStream(Bytes(byteArrayOf(0, 1, 0, 8, 0, 0, 0, 1, 0, 0, 0, 0)), 1000)
        assertFailsWith<IllegalStateException> { reset.read(1) }
    }

    @Test fun shutdown_notification_failure_still_closes_the_owned_transport() = runBlocking<Unit> {
        var closed = false
        val wire = object : Libp2pStream {
            override suspend fun read(maxBytes: Int): ByteArray = byteArrayOf()
            override suspend fun write(bytes: ByteArray) { error("Peer already closed") }
            override suspend fun close() { closed = true }
        }
        Libp2pYamuxStream(wire, 1000).close()
        assertTrue(closed)
        val failedCleanup = object : Libp2pStream by wire {
            override suspend fun close() { throw IllegalStateException("Descriptor cleanup failed") }
        }
        val failure = assertFailsWith<IllegalStateException> { Libp2pYamuxStream(failedCleanup, 1000).close() }
        assertEquals("Descriptor cleanup failed", failure.message)
    }
}
