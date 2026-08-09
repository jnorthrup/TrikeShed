package borg.trikeshed.torrent

import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Peer wire protocol handler — dispatches per-peer messages and manages pending piece requests.
 *
 * Implements stream reassembly over the uTP byte stream: the first message
 * from a peer is the 68-byte [PeerHandshake], followed by length-prefixed
 * peer wire messages. This handler buffers partial data per peer and
 * dispatches complete frames to [PeerWireMessage.decode].
 */
class PeerWireHandler(
    private val infoHashBytes: ByteArray,
    private val piecePicker: PiecePicker,
    private val blockStore: borg.trikeshed.htx.client.ipfs.BlockStore,
    private val scope: CoroutineScope,
) {
    // address → (pieceIndex → deferred)
    private val pendingPieces = ConcurrentHashMap<PeerAddress, MutableMap<Int, CompletableDeferred<ByteArray>>>()
    // address → bitfield
    private val peerBitfields = ConcurrentHashMap<PeerAddress, BitField>()
    // address → handshake received
    private val handshakeDone = ConcurrentHashMap.newKeySet<PeerAddress>()
    // address → accumulation buffer for partial frames
    private val streamBuffers = ConcurrentHashMap<PeerAddress, ByteArray>()

    /**
     * Feed a raw uTP payload chunk for a peer and dispatch complete frames.
     * The first chunk from each peer is the 68-byte handshake; subsequent
     * data is length-prefixed peer wire messages that may span multiple
     * uTP packets or pack multiple messages into one.
     */
    fun handlePeerMessage(
        address: PeerAddress,
        header: UtpHeader,
        payload: ByteArray,
        onHave: (Int) -> Unit,
    ) {
        // Accumulate incoming bytes
        val existing = streamBuffers[address] ?: ByteArray(0)
        val buf = existing + payload
        var offset = 0

        // First message must be the handshake (pstrlen=19, total 68 bytes)
        if (address !in handshakeDone) {
            if (buf.size < PeerHandshake.HANDSHAKE_SIZE) {
                streamBuffers[address] = buf
                return
            }
            val hs = PeerHandshake.decode(buf.copyOfRange(0, PeerHandshake.HANDSHAKE_SIZE))
            if (hs != null) handshakeDone.add(address)
            offset = PeerHandshake.HANDSHAKE_SIZE
        }

        // Dispatch length-prefixed frames
        while (offset + 4 <= buf.size) {
            val frameLen = ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
            val totalFrame = 4 + frameLen
            if (offset + totalFrame > buf.size) break // partial frame — wait for more data
            val frame = buf.copyOfRange(offset, offset + totalFrame)
            offset += totalFrame
            val msg = PeerWireMessage.decode(frame) ?: continue
            dispatchMessage(address, msg, onHave)
        }

        // Save leftover bytes for the next chunk
        if (offset < buf.size) {
            streamBuffers[address] = buf.copyOfRange(offset, buf.size)
        } else {
            streamBuffers.remove(address)
        }
    }

    private fun dispatchMessage(
        address: PeerAddress,
        msg: PeerWireMessage,
        onHave: (Int) -> Unit,
    ) {
        when (msg) {
            is PeerWireMessage.Have -> {
                piecePicker.setPeerHave(msg.pieceIndex)
                havePieces(address).set(msg.pieceIndex, true)
                onHave(msg.pieceIndex)
            }
            is PeerWireMessage.PWPieceBitField -> {
                peerBitfields[address] = msg.have
                for (i in 0 until msg.have.size) {
                    if (msg.have[i]) piecePicker.setPeerHave(i)
                }
            }
            is PeerWireMessage.Piece -> {
                pendingPieces[address]?.remove(msg.pieceIndex)?.complete(msg.data)
                // Store block with CID (content-addressed, DRY with IPFS blocks)
                val cid = borg.trikeshed.htx.client.ipfs.CID.sha256(msg.data)
                scope.launch { blockStore.put(cid, msg.data) }
            }
            is PeerWireMessage.Choke -> { chokedPeers.add(address) }
            is PeerWireMessage.Unchoke -> { chokedPeers.remove(address) }
            is PeerWireMessage.Interested -> { /* we may want to upload */ }
            is PeerWireMessage.NotInterested -> { /* peer doesn't want our data */ }
            is PeerWireMessage.Request -> { /* a peer is requesting from us (seeder) */ }
            is PeerWireMessage.Cancel -> { /* handle cancel */ }
            is PeerWireMessage.Port -> { /* DHT port (BEP 5) */ }
            is PeerWireMessage.Extension -> { /* BEP 10 extension protocol */ }
            else -> { /* ignore unknown */ }
        }
    }

    /** Peers that have choked us — requestPiece should skip these. */
    private val chokedPeers = ConcurrentHashMap.newKeySet<PeerAddress>()
    fun isChoked(address: PeerAddress): Boolean = address in chokedPeers

    /**
     * Register a piece request and wait for the PIECE response.
     */
    suspend fun awaitPiece(address: PeerAddress, pieceIndex: Int): ByteArray {
        val pending = pendingPieces.getOrPut(address) { mutableMapOf() }
        val deferred = CompletableDeferred<ByteArray>()
        pending[pieceIndex] = deferred
        return try {
            deferred.await()
        } catch (e: Exception) {
            pending.remove(pieceIndex)
            throw e
        }
    }

    /** Drop all state for a disconnected peer. */
    fun removePeer(address: PeerAddress) {
        pendingPieces.remove(address)
        peerBitfields.remove(address)
        streamBuffers.remove(address)
        handshakeDone.remove(address)
        chokedPeers.remove(address)
    }

    private fun havePieces(address: PeerAddress): BitField {
        return peerBitfields.getOrPut(address) {
            BitField.empty(piecePicker.rarestFirstOrder().size.coerceAtLeast(1))
        }
    }
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val result = ByteArray(this.size + other.size)
    System.arraycopy(this, 0, result, 0, this.size)
    System.arraycopy(other, 0, result, this.size, other.size)
    return result
}
