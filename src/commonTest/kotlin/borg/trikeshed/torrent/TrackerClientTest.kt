package borg.trikeshed.torrent

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import kotlin.test.*

class TrackerClientTest {
    private fun response(peers: BencodeValue, interval: Long = 120) = TorrentBencode.encode(TorrentBencode.dictionary(
        "interval" to BencodeValue.Integer(interval), "peers" to peers))

    @Test fun compactBinaryPeersRetainUnsignedOctetsAndPorts() {
        val first = byteArrayOf(192.toByte(), 168.toByte(), 1, 200.toByte(), 200.toByte(), 255.toByte())
        val second = byteArrayOf(1, 2, 3, 4, 0, 80)
        val ignored = byteArrayOf(5, 6, 7, 8, 0, 0)
        val parsed = TrackerClient.parseResponse(response(BencodeValue.Bytes(first + second + first + ignored)))
        assertEquals(120_000L, parsed.intervalMillis)
        assertEquals(listOf(PeerAddress("192.168.1.200", 51455), PeerAddress("1.2.3.4", 80)), parsed.peers.view.toList())
    }

    @Test fun dictionaryPeersAndTrackerFailureAreNotReportedAsEmptySuccess() {
        val peer = TorrentBencode.dictionary("ip" to BencodeValue.Bytes("127.0.0.1".encodeToByteArray()), "port" to BencodeValue.Integer(6881))
        val parsed = TrackerClient.parseResponse(response(BencodeValue.ListValue(listOf(peer).toSeries())))
        assertEquals(PeerAddress("127.0.0.1", 6881), parsed.peers[0])
        val failed = TorrentBencode.encode(TorrentBencode.dictionary("failure reason" to BencodeValue.Bytes("not registered".encodeToByteArray())))
        assertEquals("not registered", assertFailsWith<IllegalStateException> { TrackerClient.parseResponse(failed) }.message)
    }

    @Test fun malformedListsIntervalsAndEnvelopeAreRejected() {
        assertEquals(0, TrackerClient.parseResponse(response(BencodeValue.Bytes(byteArrayOf()))).peers.size)
        for (wire in listOf(response(BencodeValue.Bytes(ByteArray(5))), response(BencodeValue.Bytes(byteArrayOf()), 0),
            response(BencodeValue.Bytes(byteArrayOf()), 86401), "d8:intervali1ee".encodeToByteArray(),
            response(BencodeValue.ListValue(listOf(BencodeValue.Integer(1)).toSeries()))))
            assertFailsWith<IllegalArgumentException> { TrackerClient.parseResponse(wire) }
    }
}
