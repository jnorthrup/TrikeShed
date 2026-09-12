package borg.trikeshed.torrent

import kotlin.test.*

class UtpProtocolTest {
    private class Link(
        val limits: UtpLimits = UtpLimits(payloadBytes = 150, receiveBytes = 1200, sendBytes = 2400),
        clientSequence: Int = 1, serverSequence: Int = 400, connection: Int = 65535,
    ) {
        var now = 1_000_000L
        val client = UtpSocket(connection, limits = limits, clock = { now }, initialSequence = clientSequence)
        val server = UtpSocket(connection, false, limits, { now }, serverSequence)
        init {
            client.connect()
            val syn = packet(client)
            assertEquals(connection, syn.header.connId)
            server.accept(syn)
            val state = packet(server)
            assertEquals(serverSequence, state.header.seqNr)
            now += 1000
            client.receive(state)
            assertEquals(UtpState.CONNECTED, client.state)
            assertEquals((serverSequence - 1) and 65535, client.ackNr)
        }
        fun exchange() {
            repeat(30) {
                var count = 0
                while (true) { val bytes = client.takePacket() ?: break; server.receive(assertNotNull(decodeUtpPacket(bytes))); count++ }
                while (true) { val bytes = server.takePacket() ?: break; client.receive(assertNotNull(decodeUtpPacket(bytes))); count++ }
                if (count == 0) return
            }
            error("uTP exchange did not quiesce")
        }
    }
    companion object {
        private fun packet(socket: UtpSocket) = assertNotNull(decodeUtpPacket(assertNotNull(socket.takePacket())))
    }

    @Test fun wireUsesTwentyNetworkOrderBytesAndUnsignedTimestamps() {
        val header = UtpHeader(UtpType.ST_DATA, connId = 0xfedc, timestamp = 0x89abcdef,
            timestampDifference = 0x10203040, wndSize = 0x90abcdef, seqNr = 0x1234, ackNr = 0xabcd)
        assertContentEquals(byteArrayOf(0x01, 0, 0xfe.toByte(), 0xdc.toByte(),
            0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x10, 0x20, 0x30, 0x40,
            0x90.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x12, 0x34, 0xab.toByte(), 0xcd.toByte()), header.encode())
        assertEquals(header, decodeUtpHeader(header.encode()))
        assertNull(decodeUtpHeader(header.encode().also { it[0] = 2 }))
        assertNull(decodeUtpHeader(header.encode().copyOf(19)))
    }

    @Test fun extensionsAreBoundedAndRemovedBeforePayloadDelivery() {
        val header = UtpHeader(UtpType.ST_DATA, connId = 4, timestamp = 1, wndSize = 100, seqNr = 7, ackNr = 3, extension = 9)
        val wire = header.encode() + byteArrayOf(1, 2, 99, 98, 0, 4, 1, 0, 0, 0, 42, 43)
        val decoded = assertNotNull(decodeUtpPacket(wire))
        assertContentEquals(byteArrayOf(42, 43), decoded.payload)
        assertContentEquals(byteArrayOf(1, 0, 0, 0), decoded.selectiveAck)
        assertNull(decodeUtpPacket(header.copy(extension = 1).encode() + byteArrayOf(0, 3, 1, 2, 3)))
        assertNull(decodeUtpPacket(header.encode() + byteArrayOf(0, 20, 1)))
        assertNull(decodeUtpPacket(header.copy(type = UtpType.STATE, extension = 0).encode() + byteArrayOf(1)))
    }

    @Test fun establishmentUsesIncrementedIdsAndStateDoesNotConsumeSequence() {
        val link = Link()
        assertEquals(0, link.client.sendId)
        assertEquals(0, link.server.receiveId)
        val bytes = byteArrayOf(19, 66, 105, 116)
        assertEquals(bytes.size, link.server.send(bytes))
        val data = packet(link.server)
        assertEquals(400, data.header.seqNr)
        link.client.receive(data)
        assertContentEquals(bytes, link.client.read(20))
        val ack = packet(link.client)
        assertEquals(400, ack.header.ackNr)
        link.server.receive(ack)
        assertTrue(link.server.sendIdle)
        val idle = UtpSocket(5)
        idle.connect()
        assertFailsWith<IllegalStateException> { idle.send(bytes) }
    }

    @Test fun reorderedDuplicateDataArrivesOnceAndSelectiveAckRetainsTheGap() {
        val link = Link()
        val expected = ByteArray(300) { it.toByte() }
        link.client.send(expected)
        val first = packet(link.client); val second = packet(link.client)
        link.server.receive(second)
        link.server.receive(second)
        assertNull(link.server.read(1000))
        val sack = packet(link.server)
        assertEquals(first.header.seqNr - 1, sack.header.ackNr)
        assertEquals(1, sack.selectiveAck[0].toInt())
        link.client.receive(sack)
        assertEquals(150, link.client.flightBytes)
        link.now += 1_000_001
        link.client.tick()
        val retried = packet(link.client)
        assertEquals(first.header.seqNr, retried.header.seqNr)
        assertContentEquals(first.payload, retried.payload)
        link.server.receive(retried)
        assertContentEquals(expected, link.server.read(1000))
        link.exchange()
        assertTrue(link.client.sendIdle)
        assertNull(link.server.read(1000))
    }

    @Test fun cumulativeAcksWrapAndUnsentAcksOrWrongIdsDoNotReleaseFlight() {
        val link = Link(clientSequence = 65534)
        val expected = ByteArray(450) { (it * 17).toByte() }
        link.client.send(expected)
        val first = packet(link.client)
        assertEquals(65535, first.header.seqNr)
        val second = packet(link.client)
        assertEquals(0, second.header.seqNr)
        val forged = UtpPacket(UtpHeader(UtpType.STATE, connId = link.client.receiveId,
            timestamp = link.now, wndSize = 1200, seqNr = 400, ackNr = 30))
        link.client.receive(forged)
        assertEquals(300, link.client.flightBytes)
        link.server.receive(first.copy(header = first.header.copy(connId = 3)))
        assertNull(link.server.read(1000))
        link.server.receive(first); link.server.receive(second)
        link.exchange()
        assertContentEquals(expected, link.server.read(1000))
        link.exchange()
        assertTrue(link.client.sendIdle)
    }

    @Test fun receiveWindowBackpressuresAndPartialWindowMakesProgress() {
        val link = Link(UtpLimits(payloadBytes = 150, receiveBytes = 300, sendBytes = 600))
        val expected = ByteArray(600) { it.toByte() }
        assertEquals(600, link.client.send(expected))
        assertEquals(0, link.client.send(byteArrayOf(1)))
        link.exchange()
        assertFalse(link.client.sendIdle)
        assertEquals(0, link.server.receiveWindow)
        val first = assertNotNull(link.server.read(17))
        link.exchange()
        // A positive window smaller than the queued segment must split that unsent segment.
        assertEquals(0, link.server.receiveWindow)
        assertEquals(300, link.server.readableBytes)
        val rest = mutableListOf<Byte>()
        rest.addAll(first.toList())
        repeat(10) {
            link.server.read(111)?.let { rest.addAll(it.toList()) }
            link.exchange()
        }
        assertContentEquals(expected, rest.toByteArray())
        assertTrue(link.client.sendIdle)
    }

    @Test fun lostZeroWindowUpdateRecoversWithBoundedProbe() {
        val link = Link(UtpLimits(payloadBytes = 150, receiveBytes = 300, sendBytes = 600))
        val expected = ByteArray(450) { it.toByte() }
        link.client.send(expected); link.exchange()
        val first = assertNotNull(link.server.read(300))
        packet(link.server) // Lose the window reopening ACK.
        link.now += 1_000_001; link.client.tick()
        val probe = packet(link.client)
        assertEquals(1, probe.payload.size)
        link.server.receive(probe); link.exchange()
        assertContentEquals(expected, first + assertNotNull(link.server.read(300)))
        link.exchange(); assertTrue(link.client.sendIdle)
    }

    @Test fun finWaitsForEveryGapAndRetransmissionLimitIsTerminal() {
        val link = Link()
        val payload = byteArrayOf(3, 4, 5)
        link.client.send(payload)
        val data = packet(link.client)
        val fin = UtpPacket(data.header.copy(type = UtpType.FIN, seqNr = (data.header.seqNr + 1) and 65535))
        link.server.receive(fin)
        assertNull(link.server.read(100))
        assertFalse(link.server.receivedEof)
        link.server.receive(data)
        assertContentEquals(payload, link.server.read(100))
        assertContentEquals(byteArrayOf(), link.server.read(100))
        val lost = Link(UtpLimits(payloadBytes = 150, maxRetransmissions = 1))
        lost.client.send(byteArrayOf(7)); packet(lost.client)
        lost.now += 2_000_000; lost.client.tick(); packet(lost.client)
        lost.now += 4_000_000; lost.client.tick()
        assertEquals(UtpState.ERROR, lost.client.state)
        assertEquals("uTP retransmission limit", lost.client.failure)
    }

    @Test fun normalFinIsAcknowledgedWithoutDeliveringItsHeader() {
        val link = Link()
        link.client.close(); link.exchange()
        assertEquals(UtpState.CLOSED, link.client.state)
        assertTrue(link.server.receivedEof)
        assertContentEquals(byteArrayOf(), link.server.read(40))
    }
}
