package borg.trikeshed.torrent

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PeerWireHandlerTest {
    private val hash = InfoHash(ByteArray(20) { it.toByte() })
    private val peerId = ByteArray(20) { 7 }
    private fun handshake(hash: InfoHash = this.hash): ByteArray = PeerHandshake(
        reservedBytes = ByteArray(8).also { it[5] = 0x10 }, infoHash = hash.wireBytes, peerId = peerId).encode()
    private suspend fun ready(handler: PeerWireHandler) {
        handler.open()
        handler.receive(handshake() + PeerWireMessage.PWPieceBitField(BitField(byteArrayOf(0xC0.toByte()))).encode() + PeerWireMessage.Unchoke.encode())
    }

    @Test fun observedAvailabilityDoesNotInventLocalPiecesAndPendingPrecedesResponse() = runTest {
        val output = Channel<ByteArray>(8)
        val handler = PeerWireHandler(hash, 2, { 32768 }, { output.send(it) }, this)
        try {
            handler.open()
            assertTrue(handler.peerState.value.choked)
            assertFalse(handler.peerState.value.hasPiece(0))
            // BEP 3 permits omitting BITFIELD; HAVE observations may precede session configuration.
            handler.receive(handshake() + PeerWireMessage.Have(0).encode())
            assertTrue(handler.peerState.value.hasPiece(0))
            assertFalse(handler.peerState.value.hasPiece(1))
            handler.configurePieces(2) { 32768 }
            assertTrue(handler.peerState.value.hasPiece(0), "Configuration must preserve earlier HAVE")
            assertFalse(handler.peerState.value.hasPiece(1))
            assertFailsWith<IllegalStateException> { handler.requestBlock(0, 0, 4) }
            handler.receive(PeerWireMessage.Have(1).encode() + PeerWireMessage.Unchoke.encode())
            assertTrue(handler.peerState.value.hasPiece(1))
            val request = async { handler.requestBlock(1, 16384, 4) }
            assertEquals(PeerWireMessage.Request(1, 16384, 4), PeerWireMessage.decode(output.receive()))
            handler.receive(PeerWireMessage.Piece(1, 16384, byteArrayOf(1, 2, 3, 4)).encode())
            assertContentEquals(byteArrayOf(1, 2, 3, 4), request.await())
        } finally { handler.close() }
    }

    @Test fun wrongResponseTupleFailsRequestAndDisconnectSettlesWaiters() = runTest {
        val output = Channel<ByteArray>(8)
        val handler = PeerWireHandler(hash, 2, { 32768 }, { output.send(it) }, this)
        try {
            ready(handler)
            val request = async { runCatching { handler.requestBlock(0, 0, 4) } }
            output.receive()
            assertFailsWith<IllegalArgumentException> { handler.receive(PeerWireMessage.Piece(0, 4, byteArrayOf(1, 2, 3, 4)).encode()) }
            assertTrue(request.await().isFailure)
            val pending = async { runCatching { handler.requestBlock(1, 0, 4) } }
            output.receive()
            handler.close()
            assertTrue(pending.await().isFailure)
            assertEquals(ElementState.CLOSED, handler.lifecycleState)
        } finally { handler.close() }
    }

    @Test fun cancellationSendsExactCancelAndConsumesOnlyMatchingLateBlock() = runTest {
        val output = Channel<ByteArray>(8)
        val handler = PeerWireHandler(hash, 2, { 32768 }, { output.send(it) }, this)
        try {
            ready(handler)
            val request = async { handler.requestBlock(0, 16384, 4) }
            output.receive()
            request.cancelAndJoin()
            assertEquals(PeerWireMessage.Cancel(0, 16384, 4), PeerWireMessage.decode(output.receive()))
            handler.receive(PeerWireMessage.Piece(0, 16384, byteArrayOf(1, 2, 3, 4)).encode())
            assertFailsWith<IllegalArgumentException> { handler.receive(PeerWireMessage.Piece(0, 16384, byteArrayOf(1, 2, 3, 4)).encode()) }
        } finally { handler.close() }
    }

    @Test fun metadataMapsRemoteExtensionIdAndAuthenticatesEntireDictionary() = runTest {
        val bytes = TorrentBencode.encode(TorrentBencode.dictionary("payload" to BencodeValue.Bytes(ByteArray(20000) { it.toByte() })))
        val expected = InfoHash(Sha1.digest(bytes))
        val output = Channel<ByteArray>(8)
        val handler = PeerWireHandler(expected, 0, { error("metadata not installed") }, { output.send(it) }, this)
        try {
            handler.open()
            val extension = PeerWireMessage.Extension(0, TorrentBencode.encode(TorrentBencode.dictionary(
                "m" to TorrentBencode.dictionary("ut_metadata" to BencodeValue.Integer(193)),
                "metadata_size" to BencodeValue.Integer(bytes.size.toLong()))))
            handler.receive(handshake(expected) + extension.encode())
            val download = async { handler.requestMetadata() }
            for (index in 0..1) {
                val request = PeerWireMessage.decode(output.receive()) as PeerWireMessage.Extension
                assertEquals(193, request.id)
                val dictionary = TorrentBencode.decode(request.payload) as BencodeValue.Dictionary
                assertEquals(index.toLong(), (dictionary["piece"] as BencodeValue.Integer).value)
                val from = index * 16384
                handler.receive(PeerExtensions.metadata(1, 1, index, bytes.size, bytes.copyOfRange(from, minOf(from + 16384, bytes.size))).encode())
            }
            assertContentEquals(bytes, download.await())
            handler.receive(PeerExtensions.metadata(1, 0, 0).encode())
            val seeded = PeerWireMessage.decode(output.receive()) as PeerWireMessage.Extension
            assertEquals(193, seeded.id)
            val prefix = TorrentBencode.decodePrefix(seeded.payload)
            assertContentEquals(bytes.copyOf(16384), seeded.payload.copyOfRange(prefix.b, seeded.payload.size))
        } finally { handler.close() }
    }

    @Test fun badMetadataHashNeverBecomesSeedable() = runTest {
        val bytes = "d1:ai1ee".encodeToByteArray()
        val output = Channel<ByteArray>(8)
        val handler = PeerWireHandler(hash, 0, { 0 }, { output.send(it) }, this)
        try {
            handler.open()
            handler.receive(handshake() + PeerExtensions.handshake(bytes.size).encode())
            val download = async { runCatching { handler.requestMetadata() } }
            output.receive()
            handler.receive(PeerExtensions.metadata(1, 1, 0, bytes.size, bytes).encode())
            assertTrue(download.await().isFailure)
            handler.receive(PeerExtensions.metadata(1, 0, 0).encode())
            val reply = PeerWireMessage.decode(output.receive()) as PeerWireMessage.Extension
            assertEquals(2L, ((TorrentBencode.decode(reply.payload) as BencodeValue.Dictionary)["msg_type"] as BencodeValue.Integer).value)
        } finally { handler.close() }
    }

    @Test fun hashResponseMustMatchWholeRequestedRange() = runTest {
        val expected = InfoHash(ByteArray(32) { it.toByte() })
        val output = Channel<ByteArray>(8)
        val handler = PeerWireHandler(expected, 2, { 32768 }, { output.send(it) }, this)
        try {
            handler.open(); handler.receive(handshake(expected))
            val range = PeerHashRange(ByteArray(32) { 3 }, 1, 0, 2, 0)
            val request = async { handler.requestHashes(range) }
            assertTrue((PeerWireMessage.decode(output.receive()) as PeerWireMessage.HashRequest).range.matches(range))
            handler.receive(PeerWireMessage.Hashes(range, ByteArray(64) { 9 }).encode())
            assertContentEquals(ByteArray(64) { 9 }, request.await())
        } finally { handler.close() }
    }

    @Test fun bitfieldPaddingAndPieceBoundsAreValidated() = runTest {
        val handler = PeerWireHandler(hash, 2, { 32768 }, {}, this)
        try {
            handler.open(); handler.receive(handshake())
            assertFailsWith<IllegalArgumentException> { handler.receive(PeerWireMessage.PWPieceBitField(BitField(byteArrayOf(0x81.toByte()))).encode()) }
            assertFailsWith<IllegalArgumentException> { handler.receive(PeerWireMessage.Have(2).encode()) }
            assertFailsWith<IllegalArgumentException> { handler.requestBlock(0, 32767, 2) }
        } finally { handler.close() }
    }

    @Test fun cancelledUploadDoesNotSendPieceAndDrainJoinsAdmittedUpload() = runTest {
        val output = Channel<ByteArray>(8)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handler = PeerWireHandler(hash, 2, { 32768 }, { output.send(it) }, this,
            serveBlock = { entered.complete(Unit); release.await(); ByteArray(it.length) { 9 } })
        try {
            ready(handler)
            handler.send(PeerWireMessage.Unchoke); output.receive()
            handler.receive(PeerWireMessage.Request(0, 0, 4).encode())
            entered.await()
            handler.receive(PeerWireMessage.Cancel(0, 0, 4).encode())
            release.complete(Unit)
            handler.close()
            assertTrue(output.tryReceive().isFailure)
        } finally { release.complete(Unit); handler.close() }
    }
}
