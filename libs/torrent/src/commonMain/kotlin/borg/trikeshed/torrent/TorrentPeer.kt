package borg.trikeshed.torrent

/**
 * BitTorrent peer wire protocol (BEP 3).
 * Manages state machine per remote peer connection.
 */
class TorrentPeer(
    val ip: String,
    val port: Int,
    val peerId: String,
) {
    // Wire protocol message IDs
    enum class MessageId(val id: Int) {
        CHOKE(0), UNCHOKE(1), INTERESTED(2), NOT_INTERESTED(3),
        HAVE(4), BITFIELD(5), REQUEST(6), PIECE(7), CANCEL(8);
    }

    var amChoking = true
    var amInterested = false
    var peerChoking = true
    var peerInterested = false
    var bitfield: BitSet? = null  // which pieces the peer has

    /**
     * Build a handshake message (BEP 3 §1).
     * <pstrlen><pstr><reserved><info_hash><peer_id>
     */
    fun buildHandshake(infoHash: ByteArray, ourPeerId: String): ByteArray {
        val pstr = "BitTorrent protocol".encodeToByteArray()
        val reserved = ByteArray(8)
        return byteArrayOf(pstr.size.toByte()) + pstr + reserved + infoHash + ourPeerId.encodeToByteArray()
    }

    fun buildRequest(pieceIndex: Int, blockOffset: Int, blockLength: Int): ByteArray {
        val msg = ByteArray(17)  // 4 len + 1 id + 4 index + 4 offset + 4 length
        msg[3] = 13  // length prefix (big-endian, bottom byte for values < 256)
        msg[4] = MessageId.REQUEST.id.toByte()
        writeInt(msg, 5, pieceIndex)
        writeInt(msg, 9, blockOffset)
        writeInt(msg, 13, blockLength)
        return msg
    }

    fun buildInterested(): ByteArray = byteArrayOf(0, 0, 0, 1, MessageId.INTERESTED.id.toByte())
    fun buildNotInterested(): ByteArray = byteArrayOf(0, 0, 0, 1, MessageId.NOT_INTERESTED.id.toByte())

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}

/** Lightweight bit set for piece availability. */
class BitSet(val size: Int) {
    private val bits = LongArray((size + 63) / 64)
    operator fun get(index: Int): Boolean = (bits[index / 64] and (1L shl (index % 64))) != 0L
    operator fun set(index: Int, value: Boolean) {
        if (value) bits[index / 64] = bits[index / 64] or (1L shl (index % 64))
        else bits[index / 64] = bits[index / 64] and (1L shl (index % 64)).inv()
    }
    fun count(): Int = bits.sumOf { it.countOneBits() }
    fun fromBytes(data: ByteArray) {
        for (i in data.indices) {
            val b = data[i].toInt() and 0xFF
            for (bit in 0..7) if ((b and (1 shl (7 - bit))) != 0) this[i * 8 + bit] = true
        }
    }
}
