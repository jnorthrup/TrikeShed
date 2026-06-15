package borg.trikeshed.torrent

import borg.trikeshed.htx.client.ipfs.CID
import java.security.MessageDigest

/**
 * BitTorrent Peer Wire Protocol (BEP 3) message types and wire-format codec.
 *
 * The peer wire protocol runs over TCP.  It multiplexes:
 *   - piece requests/responses (payload)
 *   - HAVE / BITFIELD / CHOKE / UNCHOKE / INTERESTED / NOT_INTERESTED (signalling)
 *   - extension protocol headers (BEP 10) for v2 hybrid and ut_metadata
 */

/**
 * Peer wire protocol message types.
 */
enum class PeerMessageType(val id: Int) {
    CHOKE(0),
    UNCHOKE(1),
    INTERESTED(2),
    NOT_INTERESTED(3),
    HAVE(4),
    BITFIELD(5),
    REQUEST(6),
    PIECE(7),
    CANCEL(8),
    PORT(9),           // DHT port announcement (BEP 5)
    SUGGEST_PIECE(13), // Fast Extension (BEP 6)
    HAVE_ALL(14),      // Fast Extension
    HAVE_NONE(15),     // Fast Extension
    REJECT_REQUEST(16),// Fast Extension
    ALLOWED_FAST(17),  // Fast Extension
    // Extension protocol (BEP 10) messages carry sub-messages in payload
    EXTENSION(20),
}

/**
 * Outgoing peer wire protocol message.
 */
sealed class PeerWireMessage {
    data object Choke : PeerWireMessage()
    data object Unchoke : PeerWireMessage()
    data object Interested : PeerWireMessage()
    data object NotInterested : PeerWireMessage()
    data class Have(val pieceIndex: Int) : PeerWireMessage()
    data class PWPieceBitField(val have: BitField) : PeerWireMessage()
    data class Request(val pieceIndex: Int, val offset: Int, val length: Int) : PeerWireMessage()
    data class Piece(val pieceIndex: Int, val offset: Int, val data: ByteArray) : PeerWireMessage()
    data class Cancel(val pieceIndex: Int, val offset: Int, val length: Int) : PeerWireMessage()
    data class Port(val port: Int) : PeerWireMessage()
    data class Extension(val id: Int, val payload: ByteArray) : PeerWireMessage()

    /**
     * Encode to wire format (big-endian length-prefixed).
     * Format: <length:4><id:1><payload...>
     */
    fun encode(): ByteArray {
        val payload = when (this) {
            is Choke           -> ByteArray(0)
            is Unchoke         -> ByteArray(0)
            is Interested      -> ByteArray(0)
            is NotInterested   -> ByteArray(0)
            is Have            -> int32BE(pieceIndex)
            is PWPieceBitField -> have.bits
            is Request         -> int32BE(pieceIndex) + int32BE(offset) + int32BE(length)
            is Piece           -> int32BE(pieceIndex) + int32BE(offset) + data
            is Cancel          -> int32BE(pieceIndex) + int32BE(offset) + int32BE(length)
            is Port            -> int16BE(port)
            is Extension       -> byteOf(id) + payload
        }
        val msgId = typeId()
        val length = payload.size + (if (msgId >= 0) 1 else 0)
        return int32BE(length) + (if (msgId >= 0) byteOf(msgId) else ByteArray(0)) + payload
    }

    private fun typeId(): Int = when (this) {
        is Choke           -> 0
        is Unchoke         -> 1
        is Interested      -> 2
        is NotInterested   -> 3
        is Have            -> 4
        is PWPieceBitField -> 5
        is Request         -> 6
        is Piece           -> 7
        is Cancel          -> 8
        is Port            -> 9
        is Extension       -> 20
        else               -> -1
    }

    companion object {
        fun decode(data: ByteArray): PeerWireMessage? {
            if (data.size < 4) return null
            val len = (data[0].toInt() shl 24) or (data[1].toInt() shl 16) or (data[2].toInt() shl 8) or data[3].toInt()
            if (data.size < 4 + len) return null
            val payload = data.copyOfRange(4, 4 + len)
            return when (payload.getOrNull(0)?.toInt()) {
                0  -> Choke
                1  -> Unchoke
                2  -> Interested
                3  -> NotInterested
                4  -> Have((payload[1].toInt() shl 24) or (payload[2].toInt() shl 16) or (payload[3].toInt() shl 8) or payload[4].toInt())
                5  -> PWPieceBitField(BitField(payload.copyOfRange(1, payload.size)))
                6  -> Request(
                    (payload[1].toInt() shl 24) or (payload[2].toInt() shl 16) or (payload[3].toInt() shl 8) or payload[4].toInt(),
                    (payload[5].toInt() shl 24) or (payload[6].toInt() shl 16) or (payload[7].toInt() shl 8) or payload[8].toInt(),
                    (payload[9].toInt() shl 24) or (payload[10].toInt() shl 16) or (payload[11].toInt() shl 8) or payload[12].toInt(),
                )
                7  -> Piece(
                    (payload[1].toInt() shl 24) or (payload[2].toInt() shl 16) or (payload[3].toInt() shl 8) or payload[4].toInt(),
                    (payload[5].toInt() shl 24) or (payload[6].toInt() shl 16) or (payload[7].toInt() shl 8) or payload[8].toInt(),
                    payload.copyOfRange(13, payload.size),
                )
                8  -> Cancel(
                    (payload[1].toInt() shl 24) or (payload[2].toInt() shl 16) or (payload[3].toInt() shl 8) or payload[4].toInt(),
                    (payload[5].toInt() shl 24) or (payload[6].toInt() shl 16) or (payload[7].toInt() shl 8) or payload[8].toInt(),
                    (payload[9].toInt() shl 24) or (payload[10].toInt() shl 16) or (payload[11].toInt() shl 8) or payload[12].toInt(),
                )
                9  -> Port((payload[1].toInt() shl 8) or payload[2].toInt())
                20 -> Extension(payload.getOrNull(1)?.toInt() ?: 0, payload.copyOfRange(2, payload.size))
                else -> null
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun int32BE(v: Int): ByteArray = byteArrayOf(
    (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
)

private fun int16BE(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())

private fun byteOf(v: Int): ByteArray = byteArrayOf(v.toByte())

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val result = ByteArray(this.size + other.size)
    System.arraycopy(this, 0, result, 0, this.size)
    System.arraycopy(other, 0, result, this.size, other.size)
    return result
}

/**
 * Peer wire protocol handshake (BEP 3).
 *
 * Protocol string:
 *   BitTorrent protocol: 19 bytes + 49 bytes
 *   v2 (BEP 52) does NOT use the BitTorrent protocol handshake —
 *   it uses the MSE/CorrectionCode handshake over uTP, not TCP.
 *   This handshake is only used for the v1-compatible TCP peers.
 */
data class PeerHandshake(
    val protocol: ByteArray = "BitTorrent protocol".toByteArray(),
    val reservedBytes: ByteArray = ByteArray(8),
    val infoHash: ByteArray,
    val peerId: ByteArray,
) {
    init {
        require(protocol.size == 19) { "Protocol must be 19 bytes" }
        require(reservedBytes.size == 8) { "Reserved must be 8 bytes" }
        require(infoHash.size == 20) { "InfoHash must be 20 bytes for v1" }
        require(peerId.size == 20) { "PeerId must be 20 bytes" }
    }

    /** Build a handshake byte array. */
    fun encode(): ByteArray = protocol + reservedBytes + infoHash + peerId

    companion object {
        const val PROTOCOL_LEN = 19

        fun decode(data: ByteArray): PeerHandshake? {
            if (data.size < 68) return null
            val proto = data.copyOfRange(0, 19)
            val reserved = data.copyOfRange(19, 27)
            val infoHash = data.copyOfRange(27, 47)
            val peerId = data.copyOfRange(47, 67)
            if (!proto.contentEquals("BitTorrent protocol".toByteArray())) return null
            return PeerHandshake(proto, reserved, infoHash, peerId)
        }

        /** PeerId encoding for Azureus-style clients. */
        fun azureusPeerId(client: String = "TR", version: String = "0.01"): ByteArray {
            val s = "-${client}${version}-"
            val random = ByteArray(20 - s.length)
            java.security.SecureRandom().nextBytes(random)
            return (s.toByteArray() + random).copyOf(20)
        }
    }
}
