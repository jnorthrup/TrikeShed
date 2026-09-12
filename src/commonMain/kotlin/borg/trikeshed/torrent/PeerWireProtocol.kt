package borg.trikeshed.torrent

/** BEP 3/10/52 wire IDs. Unnegotiated extensions never imply local availability. */
enum class PeerMessageType(val id: Int) {
    CHOKE(0), UNCHOKE(1), INTERESTED(2), NOT_INTERESTED(3), HAVE(4), BITFIELD(5),
    REQUEST(6), PIECE(7), CANCEL(8), PORT(9), SUGGEST_PIECE(13), HAVE_ALL(14),
    HAVE_NONE(15), REJECT_REQUEST(16), ALLOWED_FAST(17), EXTENSION(20),
    HASH_REQUEST(21), HASHES(22), HASH_REJECT(23),
}

/** A requested block is identified by all three integers, including its exact length. */
data class PeerBlock(val pieceIndex: Int, val offset: Int, val length: Int) {
    init { require(pieceIndex >= 0 && offset >= 0 && length in 1..16384) }
}

/** BEP 52 hash range. Hashes are authenticated against the metadata root by the session. */
class PeerHashRange(root: ByteArray, val baseLayer: Int, val index: Int, val length: Int, val proofLayers: Int) {
    private val digest = root.copyOf()
    val root: ByteArray get() = digest.copyOf()
    val hashCount: Int get() = length + maxOf(0, proofLayers - length.countTrailingZeroBits() + 1)
    init {
        require(root.size == 32 && baseLayer in 0..63 && index >= 0)
        require(length in 2..512 && length and (length - 1) == 0 && index % length == 0)
        require(proofLayers in 0..63)
    }
    fun matches(other: PeerHashRange): Boolean = root.contentEquals(other.root) && baseLayer == other.baseLayer &&
        index == other.index && length == other.length && proofLayers == other.proofLayers
    internal fun encode(): ByteArray = root + peerInt32(baseLayer) + peerInt32(index) + peerInt32(length) + peerInt32(proofLayers)
}

sealed class PeerWireMessage {
    data object KeepAlive : PeerWireMessage()
    data object Choke : PeerWireMessage()
    data object Unchoke : PeerWireMessage()
    data object Interested : PeerWireMessage()
    data object NotInterested : PeerWireMessage()
    data class Have(val pieceIndex: Int) : PeerWireMessage()
    data class PWPieceBitField(val have: BitField) : PeerWireMessage()
    data class Request(val pieceIndex: Int, val offset: Int, val length: Int) : PeerWireMessage() {
        val block get() = PeerBlock(pieceIndex, offset, length)
    }
    data class Piece(val pieceIndex: Int, val offset: Int, val data: ByteArray) : PeerWireMessage()
    data class Cancel(val pieceIndex: Int, val offset: Int, val length: Int) : PeerWireMessage() {
        val block get() = PeerBlock(pieceIndex, offset, length)
    }
    data class Reject(val pieceIndex: Int, val offset: Int, val length: Int) : PeerWireMessage() {
        val block get() = PeerBlock(pieceIndex, offset, length)
    }
    data class Port(val port: Int) : PeerWireMessage()
    data class Extension(val id: Int, val payload: ByteArray) : PeerWireMessage()
    data class HashRequest(val range: PeerHashRange) : PeerWireMessage()
    data class Hashes(val range: PeerHashRange, val hashes: ByteArray) : PeerWireMessage()
    data class HashReject(val range: PeerHashRange) : PeerWireMessage()
    data class Unknown(val id: Int, val payload: ByteArray) : PeerWireMessage()

    fun encode(): ByteArray {
        if (this === KeepAlive) return ByteArray(4)
        val id: Int
        val payload: ByteArray
        when (this) {
            Choke -> { id = 0; payload = byteArrayOf() }
            Unchoke -> { id = 1; payload = byteArrayOf() }
            Interested -> { id = 2; payload = byteArrayOf() }
            NotInterested -> { id = 3; payload = byteArrayOf() }
            is Have -> { require(pieceIndex >= 0); id = 4; payload = peerInt32(pieceIndex) }
            is PWPieceBitField -> { id = 5; payload = have.bits }
            is Request -> { block; id = 6; payload = peerInt32(pieceIndex) + peerInt32(offset) + peerInt32(length) }
            is Piece -> {
                PeerBlock(pieceIndex, offset, data.size)
                id = 7; payload = peerInt32(pieceIndex) + peerInt32(offset) + data
            }
            is Cancel -> { block; id = 8; payload = peerInt32(pieceIndex) + peerInt32(offset) + peerInt32(length) }
            is Reject -> { block; id = 16; payload = peerInt32(pieceIndex) + peerInt32(offset) + peerInt32(length) }
            is Port -> { require(port in 1..65535); id = 9; payload = byteArrayOf((port ushr 8).toByte(), port.toByte()) }
            is Extension -> { require(this.id in 0..255); id = 20; payload = byteArrayOf(this.id.toByte()) + this.payload }
            is HashRequest -> { id = 21; payload = range.encode() }
            is Hashes -> { require(hashes.size == range.hashCount * 32); id = 22; payload = range.encode() + hashes }
            is HashReject -> { id = 23; payload = range.encode() }
            is Unknown -> { require(this.id in 0..255); id = this.id; payload = this.payload }
            KeepAlive -> error("handled above")
        }
        require(payload.size + 1 <= MAX_FRAME)
        return peerInt32(payload.size + 1) + byteArrayOf(id.toByte()) + payload
    }

    companion object {
        const val MAX_FRAME = 1024 * 1024
        /** Incomplete frames return null. Invalid complete frames throw; coalescing belongs to PeerWireFramer. */
        fun decode(data: ByteArray): PeerWireMessage? {
            if (data.size < 4) return null
            val size = peerReadInt(data, 0)
            require(size in 0..MAX_FRAME) { "Peer frame length exceeds bound" }
            if (data.size < size + 4) return null
            require(data.size == size + 4) { "Expected exactly one peer frame" }
            if (size == 0) return KeepAlive
            val id = data[4].toInt() and 255
            fun width(expected: Int) { require(size == expected) { "Invalid peer message $id width $size" } }
            fun integer(at: Int): Int = peerReadInt(data, at).also { require(it >= 0) { "Negative peer integer" } }
            fun block(): PeerBlock { width(13); return PeerBlock(integer(5), integer(9), integer(13)) }
            fun range(): PeerHashRange {
                require(size >= 49)
                return PeerHashRange(data.copyOfRange(5, 37), integer(37), integer(41), integer(45), integer(49))
            }
            return when (id) {
                0 -> { width(1); Choke }
                1 -> { width(1); Unchoke }
                2 -> { width(1); Interested }
                3 -> { width(1); NotInterested }
                4 -> { width(5); Have(integer(5)) }
                5 -> { require(size >= 2); PWPieceBitField(BitField(data.copyOfRange(5, data.size))) }
                6 -> block().let { Request(it.pieceIndex, it.offset, it.length) }
                7 -> { require(size in 10..16393); Piece(integer(5), integer(9), data.copyOfRange(13, data.size)) }
                8 -> block().let { Cancel(it.pieceIndex, it.offset, it.length) }
                9 -> { width(3); Port(((data[5].toInt() and 255) shl 8) or (data[6].toInt() and 255)) }
                16 -> block().let { Reject(it.pieceIndex, it.offset, it.length) }
                20 -> { require(size >= 2); Extension(data[5].toInt() and 255, data.copyOfRange(6, data.size)) }
                21 -> { width(49); HashRequest(range()) }
                22 -> {
                    val range = range()
                    val hashes = data.copyOfRange(53, data.size)
                    require(hashes.size == range.hashCount * 32)
                    Hashes(range, hashes)
                }
                23 -> { width(49); HashReject(range()) }
                else -> Unknown(id, data.copyOfRange(5, data.size))
            }
        }
    }
}

internal fun peerInt32(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
internal fun peerReadInt(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 255) shl 24) or
    ((bytes[at + 1].toInt() and 255) shl 16) or ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)

/** BEP 3 and BEP 52 both use this handshake over TCP or uTP; v2 truncates SHA-256 to 20 bytes. */
data class PeerHandshake(
    val protocol: ByteArray = "BitTorrent protocol".encodeToByteArray(),
    val reservedBytes: ByteArray = ByteArray(8),
    val infoHash: ByteArray,
    val peerId: ByteArray,
) {
    init {
        require(protocol.contentEquals("BitTorrent protocol".encodeToByteArray()))
        require(reservedBytes.size == 8 && infoHash.size == 20 && peerId.size == 20)
    }
    val extensions: Boolean get() = reservedBytes[5].toInt() and 0x10 != 0
    fun encode(): ByteArray = byteArrayOf(19) + protocol + reservedBytes + infoHash + peerId
    companion object {
        const val PROTOCOL_LEN = 19
        const val HANDSHAKE_SIZE = 68
        fun decode(data: ByteArray): PeerHandshake? {
            if (data.isNotEmpty()) require(data[0] == 19.toByte()) { "Invalid peer handshake length" }
            if (data.size < HANDSHAKE_SIZE) return null
            require(data.size == HANDSHAKE_SIZE)
            return PeerHandshake(data.copyOfRange(1, 20), data.copyOfRange(20, 28), data.copyOfRange(28, 48), data.copyOfRange(48, 68))
        }
        fun azureusPeerId(client: String = "TR", version: String = "0001"): ByteArray {
            require(client.length == 2 && version.length == 4 && (client + version).all { it.code in 33..126 })
            return "-$client$version-".encodeToByteArray() + kotlin.random.Random.nextBytes(12)
        }
    }
}

/** Single-owner framing: retain at most one bounded frame regardless of chunk fragmentation/coalescing. */
class PeerWireFramer(expectedInfoHash: ByteArray, expectedPeerId: ByteArray? = null, private val maxFrame: Int = PeerWireMessage.MAX_FRAME) {
    private val expectedHash = expectedInfoHash.copyOf().also { require(it.size == 20) }
    private val expectedPeer = expectedPeerId?.copyOf()?.also { require(it.size == 20) }
    private var buffer = ByteArray(PeerHandshake.HANDSHAKE_SIZE)
    private var used = 0
    private var target = PeerHandshake.HANDSHAKE_SIZE
    var handshake: PeerHandshake? = null
        private set
    init { require(maxFrame in 16393..PeerWireMessage.MAX_FRAME) }
    fun feed(bytes: ByteArray, message: (PeerWireMessage) -> Unit) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(target - used, bytes.size - offset)
            bytes.copyInto(buffer, used, offset, offset + count)
            used += count; offset += count
            if (handshake == null && used > 0) require(buffer[0] == 19.toByte()) { "Invalid peer handshake length" }
            if (used != target) continue
            if (handshake == null) {
                val value = requireNotNull(PeerHandshake.decode(buffer))
                require(value.infoHash.contentEquals(expectedHash)) { "Peer handshake info-hash mismatch" }
                require(expectedPeer == null || value.peerId.contentEquals(expectedPeer)) { "Peer handshake peer-id mismatch" }
                handshake = value
                target = 4; buffer = ByteArray(4); used = 0
            } else if (target == 4) {
                val length = peerReadInt(buffer, 0)
                require(length in 0..maxFrame) { "Peer frame length exceeds bound" }
                if (length == 0) { message(PeerWireMessage.KeepAlive); used = 0 }
                else { target = length + 4; buffer = buffer.copyOf(target) }
            } else {
                message(requireNotNull(PeerWireMessage.decode(buffer)))
                target = 4; buffer = ByteArray(4); used = 0
            }
        }
    }
    fun end() { require(handshake != null && used == 0) { "Truncated peer stream" } }
}
