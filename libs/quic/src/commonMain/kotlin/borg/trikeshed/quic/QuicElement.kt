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
    private val streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
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
