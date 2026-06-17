package borg.trikeshed.torrent

import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Peer wire protocol handler — dispatches per-peer messages and manages pending piece requests.
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

    /**
     * Dispatch an incoming peer wire message.
     */
    fun handlePeerMessage(
        address: PeerAddress,
        header: UtpHeader,
        payload: ByteArray,
        onHave: (Int) -> Unit,
    ) {
        val msg = PeerWireMessage.decode(payload) ?: return
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
                // Store block with CID
                val cid = borg.trikeshed.htx.client.ipfs.CID.sha256(msg.data)
                scope.launch { blockStore.put(cid, msg.data) }
            }
            is PeerWireMessage.Choke -> { /* handle choke — stop requesting */ }
            is PeerWireMessage.Unchoke -> { /* handle unchoke — resume requesting */ }
            is PeerWireMessage.Interested -> { /* handle interested — we may want to upload */ }
            is PeerWireMessage.NotInterested -> { /* handle not interested */ }
            is PeerWireMessage.Request -> { /* a peer is requesting from us (seeder) */ }
            is PeerWireMessage.Cancel -> { /* handle cancel */ }
            is PeerWireMessage.Port -> { /* DHT port (BEP 5) */ }
            is PeerWireMessage.Extension -> { /* BEP 10 extension protocol */ }
            else -> { /* ignore unknown */ }
        }
    }

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

    private fun havePieces(address: PeerAddress): BitField {
        return peerBitfields.getOrPut(address) {
            BitField.empty(piecePicker.rarestFirstOrder().size.coerceAtLeast(1))
        }
    }
}
