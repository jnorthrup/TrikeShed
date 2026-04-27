package borg.trikeshed.quic

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import kotlinx.coroutines.channels.Channel

data class QuicConfig(
    val alpn: List<String> = emptyList(),
    val maxIdleTimeoutMs: Long = 30000,
    val maxUdpPayloadSize: Int = 1350,
    val initialVersion: QuicVersion = QuicVersions.VERSION_1,
)

/**
 * QUIC version numbers (RFC 9000 §15).
 * VERSION_1 = 0x00000001 is the current standard.
 * Version negotiation uses 0x00000000 as a sentinel.
 */
typealias QuicVersion = UInt

object QuicVersions {
    const val NEGOTIATION: UInt = 0x0000_0000u
    const val VERSION_1: UInt   = 0x0000_0001u
    const val DRAFT_29: UInt    = 0xff00_001du
    const val DRAFT_27: UInt    = 0xff00_001bu
}

/**
 * QUIC long header packet types (RFC 9000 §17.2).
 * The two high bits of the first byte determine long vs short header.
 */
enum class QuicLongPacketType(val code: UByte) {
    INITIAL(0x00u),
    ZERO_RTT(0x01u),
    HANDSHAKE(0x02u),
    RETRY(0x03u),
}

/**
 * QUIC short header packet type (RFC 9000 §17.3).
 * Short header packets always have bit 7 clear.
 */
enum class QuicShortPacketType(val code: UByte) {
    ONE_RTT(0x40u),  // spin bit set
    ONE_RTT_NO_SPIN(0x00u),  // spin bit clear
}

// ── Variable-length integer encoding (RFC 9000 §16) ──────────────────────────

/**
 * QUIC variable-length integer encoding (RFC 9000 §16).
 * Encodes a 62-bit unsigned integer in 1, 2, 4, or 8 bytes.
 *
 * Two high bits of the first byte determine length:
 *   00 → 1 byte  (value fits in 6 bits,  0..63)
 *   01 → 2 bytes (value fits in 14 bits, 0..16383)
 *   10 → 4 bytes (value fits in 30 bits, 0..1073741823)
 *   11 → 8 bytes (value fits in 62 bits, 0..4611686018427387903)
 */
object QuicVarInt {
    const val MAX_VALUE: ULong = 0x3FFF_FFFF_FFFF_FFFFuL  // 2^62 - 1

    /** Return the number of bytes needed to encode [value]. */
    fun encodedLen(value: ULong): Int = when {
        value <= 63uL          -> 1
        value <= 16383uL       -> 2
        value <= 1073741823uL  -> 4
        else                   -> 8
    }

    /** Encode [value] into [dst] starting at [offset]. Returns number of bytes written. */
    fun encode(value: ULong, dst: ByteArray, offset: Int = 0): Int {
        require(value <= MAX_VALUE) { "QUIC varint overflow: $value > $MAX_VALUE" }
        return when {
            value <= 63uL -> {
                dst[offset] = value.toByte()
                1
            }
            value <= 16383uL -> {
                dst[offset]     = (value shr 8).toByte().let { (it.toInt() or 0x40).toByte() }
                dst[offset + 1] = value.toByte()
                2
            }
            value <= 1073741823uL -> {
                dst[offset]     = (value shr 24).toByte().let { (it.toInt() or 0x80).toByte() }
                dst[offset + 1] = (value shr 16).toByte()
                dst[offset + 2] = (value shr 8).toByte()
                dst[offset + 3] = value.toByte()
                4
            }
            else -> {
                dst[offset]     = (value shr 56).toByte().let { (it.toInt() or 0xC0).toByte() }
                dst[offset + 1] = (value shr 48).toByte()
                dst[offset + 2] = (value shr 40).toByte()
                dst[offset + 3] = (value shr 32).toByte()
                dst[offset + 4] = (value shr 24).toByte()
                dst[offset + 5] = (value shr 16).toByte()
                dst[offset + 6] = (value shr 8).toByte()
                dst[offset + 7] = value.toByte()
                8
            }
        }
    }

    /** Decode a QUIC varint from [src] at [offset]. Returns (value, bytesConsumed). */
    fun decode(src: ByteArray, offset: Int = 0): Pair<ULong, Int> {
        val first = src[offset].toInt() and 0xFF
        val tag = first shr 6
        return when (tag) {
            0 -> (first.toULong() and 0x3Fu) to 1
            1 -> {
                val value = ((first and 0x3F) shl 8) or (src[offset + 1].toInt() and 0xFF)
                value.toULong() to 2
            }
            2 -> {
                var value = 0L
                repeat(4) { i -> value = (value shl 8) or (src[offset + i].toInt() and 0xFF).toLong() }
                (value and 0x3FFFFFFF).toULong() to 4
            }
            3 -> {
                var value = 0L
                repeat(8) { i -> value = (value shl 8) or (src[offset + i].toInt() and 0xFF).toLong() }
                (value and 0x3FFFFFFF_FFFFFFFFL).toULong() to 8
            }
            else -> error("unreachable")
        }
    }
}

// ── Packet Header sealed hierarchy (RFC 9000 §17) ─────────────────────────────

/**
 * QUIC packet header — sealed class hierarchy modeling RFC 9000 §17.
 * Long header (bit 7=1): used before 1-RTT keys are established.
 * Short header (bit 7=0): used after 1-RTT keys are negotiated.
 */
sealed class QuicPacketHeader(
    open val dstConnectionId: ByteArray,
) {
    /**
     * RFC 9000 §17.2 — Long Header Packet.
     * Present during connection establishment (Initial, Handshake, 0-RTT, Retry).
     */
    sealed class Long(
        val version: QuicVersion,
        override val dstConnectionId: ByteArray,
        val srcConnectionId: ByteArray,
    ) : QuicPacketHeader(dstConnectionId) {

        /** Client initial packet — carries CRYPTO frame with TLS ClientHello */
        class Initial(
            val token: ByteArray,
            val packetNumber: ULong,
            val payload: ByteArray,
            version: QuicVersion,
            dstConnectionId: ByteArray,
            srcConnectionId: ByteArray,
        ) : Long(version, dstConnectionId, srcConnectionId)

        /** 0-RTT early data — resumption of a prior connection */
        class ZeroRtt(
            val packetNumber: ULong,
            val payload: ByteArray,
            version: QuicVersion,
            dstConnectionId: ByteArray,
            srcConnectionId: ByteArray,
        ) : Long(version, dstConnectionId, srcConnectionId)

        /** Handshake packet — carries remaining TLS handshake messages */
        class Handshake(
            val packetNumber: ULong,
            val payload: ByteArray,
            version: QuicVersion,
            dstConnectionId: ByteArray,
            srcConnectionId: ByteArray,
        ) : Long(version, dstConnectionId, srcConnectionId)

        /** Stateless retry — server responds to Initial with address validation */
        class Retry(
            val retryToken: ByteArray,
            val retryIntegrityTag: ByteArray,
            version: QuicVersion,
            dstConnectionId: ByteArray,
            srcConnectionId: ByteArray,
        ) : Long(version, dstConnectionId, srcConnectionId)
    }

    /**
     * RFC 9000 §17.3 — Short Header Packet.
     * Used after 1-RTT keys are negotiated. Protected payload.
     */
    data class Short(
        override val dstConnectionId: ByteArray,
        val spinBit: Boolean = false,
        val reservedBits: UByte = 0u,
        val keyPhase: Boolean = false,
        val packetNumberLength: UByte = 2u,
        val packetNumber: ULong,
        val protectedPayload: ByteArray,
    ) : QuicPacketHeader(dstConnectionId)

    // ── helpers ────────────────────────────────────────────────────────────

    /** The first byte encodes header form (bit 7) and fixed bit (bit 6). */
    val headerFormBit: Boolean get() = true  // long header
    val fixedBit: Boolean get() = true       // always 1 in QUIC v1
}

val QuicKey: AsyncContextKey<QuicElement> = QuicElement.Key

suspend fun openQuicElement(config: QuicConfig = QuicConfig()): QuicElement =
    QuicElement(config).also { it.open() }

class QuicElement(
    val config: QuicConfig = QuicConfig(),
   val streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
) : AsyncContextElement(), StreamTransport {
    companion object Key : AsyncContextKey<QuicElement>("QuicKey", 1L shl 4)

    override val key: AsyncContextKey<QuicElement>
        get() = Key

    override suspend fun openStream(): StreamHandle {
        requireState(ElementState.OPEN)
        val streamId = (streams.keys.maxOrNull() ?: -1) + 1
        val streamHandle = StreamHandle(
            id = streamId,
            send = Channel(Channel.BUFFERED),
            recv = Channel(Channel.BUFFERED),
        )
        streams[streamId] = streamHandle
        return streamHandle
    }

    override val activeStreams: Int get() = streams.size

    suspend fun connect(host: String, port: Int): StreamHandle {
        requireState(ElementState.OPEN)
        return openStream()
    }
}
