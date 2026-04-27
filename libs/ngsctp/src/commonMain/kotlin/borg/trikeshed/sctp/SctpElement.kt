package borg.trikeshed.sctp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import kotlinx.coroutines.channels.Channel

enum class SctpChunkType { DATA, INIT, INIT_ACK, SACK, HEARTBEAT, COOKIE_ECHO, COOKIE_ACK }

enum class SctpState {
    CLOSED,
    COOKIE_WAIT,
    COOKIE_ECHOED,
    ESTABLISHED,
    SHUTDOWN_PENDING,
    SHUTDOWN_SENT,
    SHUTDOWN_RECEIVED,
    SHUTDOWN_ACK_SENT,
}

sealed class SctpError(message: String) : RuntimeException(message) {
    class BindFailed(details: String) : SctpError(details)
    class ConnectFailed(details: String) : SctpError(details)
    class Closed : SctpError("SCTP element is closed")
}

data class SctpAssociation(val associationId: Long, val state: SctpState)

// ── SCTP Chunk encoding (RFC 4960) ──────────────────────────────────────────

/** Opaque chunk header: type (1 byte) + flags (1) + length (2) = 4 bytes. */
data class SctpChunkHeader(
    val type: SctpChunkType,
    val flags: UByte = 0u,
    val length: UShort,
)

/**
 * SCTP INIT chunk (RFC 4960 §3.3.2).
 *
 * Fixed fields (16 bytes):
 *   Initiate Tag            (32 bits)
 *   Advertised Receiver Window Credit (32 bits)
 *   Number of Outbound Streams   (16 bits)
 *   Number of Inbound Streams    (16 bits)
 *   Initial TSN              (32 bits)
 *
 * Followed by optional variable-length parameters.
 */
data class SctpInitChunk(
    val initiateTag: UInt,
    val aRwnd: UInt,
    val outboundStreams: UShort,
    val inboundStreams: UShort,
    val initialTsn: UInt,
) {
    val header: SctpChunkHeader
        get() = SctpChunkHeader(SctpChunkType.INIT, length = CHUNK_FIXED_LENGTH)

    fun encode(): ByteArray {
        val buf = ByteArray(CHUNK_FIXED_LENGTH.toInt())
        var off = 0
        buf[off++] = SctpChunkType.INIT.ordinal.toByte()  // type
        buf[off++] = 0                                      // flags
        putUShort(buf, off, CHUNK_FIXED_LENGTH); off += 2  // length
        putUInt(buf, off, initiateTag); off += 4
        putUInt(buf, off, aRwnd); off += 4
        putUShort(buf, off, outboundStreams); off += 2
        putUShort(buf, off, inboundStreams); off += 2
        putUInt(buf, off, initialTsn)
        return buf
    }

    companion object {
        const val CHUNK_FIXED_LENGTH: UShort = 20u  // 4 header + 16 fixed fields

        fun decode(bytes: ByteArray): SctpInitChunk {
            require(bytes.size >= CHUNK_FIXED_LENGTH.toInt()) { "INIT too short: ${bytes.size} < $CHUNK_FIXED_LENGTH" }
            var off = 4  // skip type+flags+length
            val initiateTag = getUInt(bytes, off); off += 4
            val aRwnd       = getUInt(bytes, off); off += 4
            val outStreams  = getUShort(bytes, off); off += 2
            val inStreams   = getUShort(bytes, off); off += 2
            val initialTsn  = getUInt(bytes, off)
            return SctpInitChunk(initiateTag, aRwnd, outStreams, inStreams, initialTsn)
        }
    }
}

/**
 * SCTP INIT_ACK chunk (RFC 4960 §3.3.3).
 *
 * Identical fixed fields to INIT, with type=2.
 */
data class SctpInitAckChunk(
    val initiateTag: UInt,
    val aRwnd: UInt,
    val outboundStreams: UShort,
    val inboundStreams: UShort,
    val initialTsn: UInt,
) {
    val header: SctpChunkHeader
        get() = SctpChunkHeader(SctpChunkType.INIT_ACK, length = CHUNK_FIXED_LENGTH)

    fun encode(): ByteArray {
        val buf = ByteArray(CHUNK_FIXED_LENGTH.toInt())
        var off = 0
        buf[off++] = SctpChunkType.INIT_ACK.ordinal.toByte()  // type
        buf[off++] = 0                                          // flags
        putUShort(buf, off, CHUNK_FIXED_LENGTH); off += 2      // length
        putUInt(buf, off, initiateTag); off += 4
        putUInt(buf, off, aRwnd); off += 4
        putUShort(buf, off, outboundStreams); off += 2
        putUShort(buf, off, inboundStreams); off += 2
        putUInt(buf, off, initialTsn)
        return buf
    }

    companion object {
        const val CHUNK_FIXED_LENGTH: UShort = 20u

        fun decode(bytes: ByteArray): SctpInitAckChunk {
            require(bytes.size >= CHUNK_FIXED_LENGTH.toInt()) { "INIT_ACK too short: ${bytes.size} < $CHUNK_FIXED_LENGTH" }
            var off = 4
            val initiateTag = getUInt(bytes, off); off += 4
            val aRwnd       = getUInt(bytes, off); off += 4
            val outStreams  = getUShort(bytes, off); off += 2
            val inStreams   = getUShort(bytes, off); off += 2
            val initialTsn  = getUInt(bytes, off)
            return SctpInitAckChunk(initiateTag, aRwnd, outStreams, inStreams, initialTsn)
        }
    }
}

// ── SACK chunk (RFC 4960 §3.3.4) ────────────────────────────────────────────

/**
 * A single gap-ack block: [start, end] TSN offsets relative to cumulative TSN.
 * start = (gapStartBlock * 1) — how many TSNs after cumulative are NOT received before the gap.
 * end   = (gapEndBlock * 1)   — how many TSNs after cumulative are received at the gap end.
 */
data class SctpGapAckBlock(
    val start: UShort,
    val end: UShort,
)

/**
 * SCTP SACK chunk (RFC 4960 §3.3.4).
 *
 * Fixed fields (16 bytes including 4-byte header):
 *   Cumulative TSN Ack              (32 bits)
 *   Advertised Receiver Window      (32 bits)
 *   Number of Gap Ack Blocks        (16 bits)
 *   Number of Duplicate TSNs        (16 bits)
 *
 * Followed by repeatable Gap Ack Blocks (8 bytes each) and Duplicate TSNs (4 bytes each).
 */
data class SctpSackChunk(
    val cumulativeTsnAck: UInt,
    val aRwnd: UInt,
    val gapAckBlocks: List<SctpGapAckBlock> = emptyList(),
    val duplicateTsns: List<UInt> = emptyList(),
) {
    val chunkLength: UShort
        get() = (FIXED_LENGTH + 8 * gapAckBlocks.size + 4 * duplicateTsns.size).toUShort()

    val header: SctpChunkHeader
        get() = SctpChunkHeader(SctpChunkType.SACK, length = chunkLength)

    fun encode(): ByteArray {
        val buf = ByteArray(chunkLength.toInt())
        var off = 0
        buf[off++] = SctpChunkType.SACK.ordinal.toByte()   // type
        buf[off++] = 0                                       // flags
        putUShort(buf, off, chunkLength); off += 2          // length
        putUInt(buf, off, cumulativeTsnAck); off += 4
        putUInt(buf, off, aRwnd); off += 4
        putUShort(buf, off, gapAckBlocks.size.toUShort()); off += 2
        putUShort(buf, off, duplicateTsns.size.toUShort()); off += 2
        for (gap in gapAckBlocks) {
            putUShort(buf, off, gap.start); off += 2
            putUShort(buf, off, gap.end); off += 2
        }
        for (dup in duplicateTsns) {
            putUInt(buf, off, dup); off += 4
        }
        return buf
    }

    companion object {
        const val FIXED_LENGTH: Int = 16  // 4 header + 4 cumTSN + 4 aRwnd + 2 gaps + 2 dups

        fun decode(bytes: ByteArray): SctpSackChunk {
            require(bytes.size >= FIXED_LENGTH) { "SACK too short: ${bytes.size} < $FIXED_LENGTH" }
            var off = 4  // skip type+flags+length
            val cumulativeTsnAck = getUInt(bytes, off); off += 4
            val aRwnd           = getUInt(bytes, off); off += 4
            val numGaps         = getUShort(bytes, off).toInt(); off += 2
            val numDups         = getUShort(bytes, off).toInt(); off += 2

            val gaps = buildList(numGaps) {
                repeat(numGaps) {
                    val start = getUShort(bytes, off); off += 2
                    val end   = getUShort(bytes, off); off += 2
                    add(SctpGapAckBlock(start, end))
                }
            }
            val dups = buildList(numDups) {
                repeat(numDups) {
                    add(getUInt(bytes, off)); off += 4
                }
            }
            return SctpSackChunk(cumulativeTsnAck, aRwnd, gaps, dups)
        }
    }
}

// ── Primitive encoding helpers (big-endian) ─────────────────────────────────

private fun putUShort(buf: ByteArray, off: Int, value: UShort) {
    val v = value.toInt()
    buf[off]     = (v shr 8).toByte()
    buf[off + 1] = v.toByte()
}

private fun putUInt(buf: ByteArray, off: Int, value: UInt) {
    var v = value
    repeat(4) { i -> buf[off + 3 - i] = v.toByte(); v = v shr 8 }
}

private fun getUShort(buf: ByteArray, off: Int): UShort =
    ((buf[off].toInt() and 0xFF) shl 8 or (buf[off + 1].toInt() and 0xFF)).toUShort()

private fun getUInt(buf: ByteArray, off: Int): UInt {
    var v = 0u
    repeat(4) { i -> v = (v shl 8) or (buf[off + i].toInt() and 0xFF).toUInt() }
    return v
}

val SctpKey: AsyncContextKey<SctpElement> = SctpElement.Key

suspend fun openSctpElement(): SctpElement =
    SctpElement().also { it.open() }

class SctpElement(
    private val streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
) : AsyncContextElement(), StreamTransport {
    companion object Key : AsyncContextKey<SctpElement>("SctpKey", 1L shl 3)

    override val key: AsyncContextKey<SctpElement>
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

    suspend fun bind(port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        return SctpAssociation(associationId = port.toLong(), state = SctpState.CLOSED)
    }

    suspend fun connect(host: String, port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        return SctpAssociation(associationId = (host.hashCode().toLong() shl 32) xor port.toLong(), state = SctpState.ESTABLISHED)
    }
}
