package borg.trikeshed.sctp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import borg.trikeshed.lib.*
import kotlinx.coroutines.channels.Channel

enum class SctpChunkType(val code: Byte) {
    DATA(0),
    INIT(1),
    INIT_ACK(2),
    SACK(3),
    HEARTBEAT(4),
    HEARTBEAT_ACK(5),
    ABORT(6),
    SHUTDOWN(7),
    SHUTDOWN_ACK(8),
    ERROR(9),
    COOKIE_ECHO(10),
    COOKIE_ACK(11),
    ECNE(12),
    CWR(13),
    SHUTDOWN_COMPLETE(14);

    companion object {
        fun fromCode(code: Byte): SctpChunkType? = values().firstOrNull { it.code == code }
    }
}

/** Multi-homing path state for SCTP failover (RFC 4960 §6.4). */
enum class PathState {
    /** Path is actively used for data transmission. */
    ACTIVE,
    /** Path has failed heartbeats and is not used. */
    INACTIVE,
    /** Path has not yet been probed. */
    UNKNOWN,
}

/**
 * Per-path status for multi-homing failover tracking.
 *
 * @param address The destination address (host:port or IP).
 * @param state Current path state.
 * @param failures Consecutive heartbeat failures on this path.
 */
data class PathStatus(
    val address: String,
    val state: PathState = PathState.UNKNOWN,
    val failures: Int = 0,
)

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

typealias SctpAssociation = Join<Long, SctpState>

fun SctpAssociation(associationId: Long, state: SctpState): SctpAssociation = associationId j state
val SctpAssociation.associationId: Long get() = a
val SctpAssociation.state: SctpState get() = b

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
        val buf = borg.trikeshed.userspace.nio.ByteBuffer.allocate(CHUNK_FIXED_LENGTH.toInt())
        buf.put(SctpChunkType.INIT.code)  // type
        buf.put(0)                                      // flags
        buf.putShort(CHUNK_FIXED_LENGTH.toShort())      // length
        buf.putInt(initiateTag.toInt())
        buf.putInt(aRwnd.toInt())
        buf.putShort(outboundStreams.toShort())
        buf.putShort(inboundStreams.toShort())
        buf.putInt(initialTsn.toInt())
        return buf.array()
    }

    companion object {
        const val CHUNK_FIXED_LENGTH: UShort = 20u  // 4 header + 16 fixed fields

        fun decode(bytes: ByteArray): SctpInitChunk {
            require(bytes.size >= CHUNK_FIXED_LENGTH.toInt()) { "INIT too short: ${bytes.size} < $CHUNK_FIXED_LENGTH" }
            val buf = borg.trikeshed.userspace.nio.ByteBuffer.wrap(bytes).position(4) // skip type+flags+length
            val initiateTag = buf.getInt().toUInt()
            val aRwnd       = buf.getInt().toUInt()
            val outStreams  = (buf.getShort().toInt() and 0xFFFF).toUShort()
            val inStreams   = (buf.getShort().toInt() and 0xFFFF).toUShort()
            val initialTsn  = buf.getInt().toUInt()
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
        val buf = borg.trikeshed.userspace.nio.ByteBuffer.allocate(CHUNK_FIXED_LENGTH.toInt())
        buf.put(SctpChunkType.INIT_ACK.code)  // type
        buf.put(0)                                          // flags
        buf.putShort(CHUNK_FIXED_LENGTH.toShort())          // length
        buf.putInt(initiateTag.toInt())
        buf.putInt(aRwnd.toInt())
        buf.putShort(outboundStreams.toShort())
        buf.putShort(inboundStreams.toShort())
        buf.putInt(initialTsn.toInt())
        return buf.array()
    }

    companion object {
        const val CHUNK_FIXED_LENGTH: UShort = 20u

        fun decode(bytes: ByteArray): SctpInitAckChunk {
            require(bytes.size >= CHUNK_FIXED_LENGTH.toInt()) { "INIT_ACK too short: ${bytes.size} < $CHUNK_FIXED_LENGTH" }
            val buf = borg.trikeshed.userspace.nio.ByteBuffer.wrap(bytes).position(4)
            val initiateTag = buf.getInt().toUInt()
            val aRwnd       = buf.getInt().toUInt()
            val outStreams  = (buf.getShort().toInt() and 0xFFFF).toUShort()
            val inStreams   = (buf.getShort().toInt() and 0xFFFF).toUShort()
            val initialTsn  = buf.getInt().toUInt()
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
typealias SctpGapAckBlock = Join<UShort, UShort>

fun SctpGapAckBlock(start: UShort, end: UShort): SctpGapAckBlock = start j end
val SctpGapAckBlock.start: UShort get() = a
val SctpGapAckBlock.end: UShort get() = b

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
    val gapAckBlocks: Series<SctpGapAckBlock> = borg.trikeshed.lib.emptySeriesOf(),
    val duplicateTsns: Series<UInt> = borg.trikeshed.lib.emptySeriesOf(),
) {
    val chunkLength: UShort
        get() = (FIXED_LENGTH + 8 * gapAckBlocks.size + 4 * duplicateTsns.size).toUShort()

    val header: SctpChunkHeader
        get() = SctpChunkHeader(SctpChunkType.SACK, length = chunkLength)

    fun encode(): ByteArray {
        val buf = borg.trikeshed.userspace.nio.ByteBuffer.allocate(chunkLength.toInt())
        buf.put(SctpChunkType.SACK.code)   // type
        buf.put(0)                                       // flags
        buf.putShort(chunkLength.toShort())          // length
        buf.putInt(cumulativeTsnAck.toInt())
        buf.putInt(aRwnd.toInt())
        buf.putShort(gapAckBlocks.size.toShort())
        buf.putShort(duplicateTsns.size.toShort())
        gapAckBlocks.view.forEach { gap ->
            buf.putShort(gap.start.toShort())
            buf.putShort(gap.end.toShort())
        }
        duplicateTsns.view.forEach { dup ->
            buf.putInt(dup.toInt())
        }
        return buf.array()
    }

    companion object {
        const val FIXED_LENGTH: Int = 16  // 4 header + 4 cumTSN + 4 aRwnd + 2 gaps + 2 dups

        fun decode(bytes: ByteArray): SctpSackChunk {
            require(bytes.size >= FIXED_LENGTH) { "SACK too short: ${bytes.size} < $FIXED_LENGTH" }
            val buf = borg.trikeshed.userspace.nio.ByteBuffer.wrap(bytes).position(4) // skip type+flags+length
            val cumulativeTsnAck = buf.getInt().toUInt()
            val aRwnd           = buf.getInt().toUInt()
            val numGaps         = buf.getShort().toInt() and 0xFFFF
            val numDups         = buf.getShort().toInt() and 0xFFFF

            val gaps: Series<SctpGapAckBlock> = numGaps j {
                val start = buf.getShort().toUShort()
                val end   = buf.getShort().toUShort()
                SctpGapAckBlock(start, end)
            }
            val dups: Series<UInt> = numDups j {
                buf.getInt().toUInt()
            }
            return SctpSackChunk(cumulativeTsnAck, aRwnd, gaps, dups)
        }
    }
}

// ── COOKIE_ECHO / COOKIE_ACK chunks (RFC 4960 §3.3.10-3.3.11) ────────────────

/**
 * SCTP COOKIE_ECHO chunk (RFC 4960 §3.3.10).
 *
 * Carries the opaque cookie received in INIT_ACK back to the server.
 * The cookie is variable-length; the chunk length encodes its size.
 */
data class SctpCookieEchoChunk(
    val cookie: ByteArray,
) {
    val chunkLength: UShort get() = (HEADER_LENGTH + cookie.size.toUShort()).toUShort()

    val header: SctpChunkHeader
        get() = SctpChunkHeader(SctpChunkType.COOKIE_ECHO, length = chunkLength)

    fun encode(): ByteArray {
        val buf = borg.trikeshed.userspace.nio.ByteBuffer.allocate(chunkLength.toInt())
        buf.put(SctpChunkType.COOKIE_ECHO.code)  // type
        buf.put(0)                                              // flags
        buf.putShort(chunkLength.toShort())                     // length
        buf.put(cookie)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SctpCookieEchoChunk) return false
        return cookie.contentEquals(other.cookie)
    }

    override fun hashCode(): Int = cookie.contentHashCode()

    companion object {
        const val HEADER_LENGTH: UShort = 4u

        fun decode(bytes: ByteArray): SctpCookieEchoChunk {
            require(bytes.size >= HEADER_LENGTH.toInt()) { "COOKIE_ECHO too short: ${bytes.size} < $HEADER_LENGTH" }
            val buf = borg.trikeshed.userspace.nio.ByteBuffer.wrap(bytes).position(2)
            val totalLen = buf.getShort().toInt() and 0xFFFF
            require(bytes.size >= totalLen) { "COOKIE_ECHO truncated: ${bytes.size} < $totalLen" }
            val cookie = bytes.copyOfRange(HEADER_LENGTH.toInt(), totalLen)
            return SctpCookieEchoChunk(cookie)
        }
    }
}

/**
 * SCTP COOKIE_ACK chunk (RFC 4960 §3.3.11).
 *
 * Sent by the server upon successfully validating a COOKIE_ECHO.
 * Fixed 4-byte chunk with no payload.
 */
object SctpCookieAckChunk {
    const val CHUNK_LENGTH: UShort = 4u

    val header: SctpChunkHeader
        get() = SctpChunkHeader(SctpChunkType.COOKIE_ACK, length = CHUNK_LENGTH)

    fun encode(): ByteArray = byteArrayOf(
        SctpChunkType.COOKIE_ACK.code,  // type
        0,                                            // flags
        0, 4,                                         // length = 4 (big-endian)
    )

    fun decode(bytes: ByteArray) {
        require(bytes.size >= CHUNK_LENGTH.toInt()) { "COOKIE_ACK too short: ${bytes.size} < $CHUNK_LENGTH" }
    }
}

// ── SHUTDOWN / SHUTDOWN_ACK / SHUTDOWN_COMPLETE chunks (RFC 4960 §3.3.12-3.3.14) ──

/**
 * SCTP SHUTDOWN chunk (RFC 4960 §3.3.12).
 */
data class SctpShutdownChunk(
    val cumulativeTsnAck: UInt,
) {
    val header: SctpChunkHeader
        get() = SctpChunkHeader(SctpChunkType.SHUTDOWN, length = CHUNK_LENGTH)

    fun encode(): ByteArray {
        val buf = borg.trikeshed.userspace.nio.ByteBuffer.allocate(CHUNK_LENGTH.toInt())
        buf.put(SctpChunkType.SHUTDOWN.code)
        buf.put(0)
        buf.putShort(CHUNK_LENGTH.toShort())
        buf.putInt(cumulativeTsnAck.toInt())
        return buf.array()
    }

    companion object {
        const val CHUNK_LENGTH: UShort = 8u

        fun decode(bytes: ByteArray): SctpShutdownChunk {
            require(bytes.size >= CHUNK_LENGTH.toInt()) { "SHUTDOWN too short: ${bytes.size} < $CHUNK_LENGTH" }
            val buf = borg.trikeshed.userspace.nio.ByteBuffer.wrap(bytes).position(4)
            val cumulativeTsnAck = buf.getInt().toUInt()
            return SctpShutdownChunk(cumulativeTsnAck)
        }
    }
}

/**
 * SCTP SHUTDOWN_ACK chunk (RFC 4960 §3.3.13).
 */
object SctpShutdownAckChunk {
    const val CHUNK_LENGTH: UShort = 4u

    val header: SctpChunkHeader
        get() = SctpChunkHeader(SctpChunkType.SHUTDOWN_ACK, length = CHUNK_LENGTH)

    fun encode(): ByteArray = byteArrayOf(
        SctpChunkType.SHUTDOWN_ACK.code,
        0,
        0, 4,
    )

    fun decode(bytes: ByteArray) {
        require(bytes.size >= CHUNK_LENGTH.toInt()) { "SHUTDOWN_ACK too short: ${bytes.size} < $CHUNK_LENGTH" }
    }
}

/**
 * SCTP SHUTDOWN_COMPLETE chunk (RFC 4960 §3.3.14).
 */
object SctpShutdownCompleteChunk {
    const val CHUNK_LENGTH: UShort = 4u

    val header: SctpChunkHeader
        get() = SctpChunkHeader(SctpChunkType.SHUTDOWN_COMPLETE, length = CHUNK_LENGTH)

    fun encode(): ByteArray = byteArrayOf(
        SctpChunkType.SHUTDOWN_COMPLETE.code,
        0,
        0, 4,
    )

    fun decode(bytes: ByteArray) {
        require(bytes.size >= CHUNK_LENGTH.toInt()) { "SHUTDOWN_COMPLETE too short: ${bytes.size} < $CHUNK_LENGTH" }
    }
}

val SctpKey: AsyncContextKey<SctpElement> = SctpElement.Key

suspend fun openSctpElement(): SctpElement =
    SctpElement().also { it.open() }

class SctpElement(
   val streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
   val associations: MutableMap<Long, SctpState> = mutableMapOf(),
   val paths: List<String> = emptyList(),          // multi-homing: active path addresses
   val congestionControl: String = "cubic",         // cubic | hstcp | rack — deterministic only
   val wire: SctpWire = SctpWire.default,           // packet boundary SPI; loopback unless registered
) : AsyncContextElement(), StreamTransport {
    companion object Key : AsyncContextKey<SctpElement>()

    override val key: AsyncContextKey<SctpElement>
        get() = Key

    /** Per-path failover tracking — lazy-initialized from [paths] on first access. */
    val _pathStatuses: MutableMap<String, PathStatus> by lazy {
        paths.associateTo(mutableMapOf()) { it to PathStatus(address = it) }
    }

    /** Current primary path — first ACTIVE path, or null if none are active. */
    val primaryPath: PathStatus?
        get() = _pathStatuses.values.firstOrNull { it.state == PathState.ACTIVE }
            ?: _pathStatuses.values.firstOrNull { it.state == PathState.UNKNOWN }

    /**
     * Mark [failedPath] as INACTIVE and return the next available ACTIVE path
     * for failover. Returns null if no paths remain.
     */
    fun failover(failedPath: String): PathStatus? {
        val current = _pathStatuses[failedPath] ?: return primaryPath
        _pathStatuses[failedPath] = current.copy(
            state = PathState.INACTIVE,
            failures = current.failures + 1,
        )
        return primaryPath
    }

    /** Mark [path] as ACTIVE (recovery after successful heartbeat probe). */
    fun recoverPath(path: String): PathStatus {
        val current = _pathStatuses[path] ?: PathStatus(address = path)
        val recovered = current.copy(
            state = PathState.ACTIVE,
            failures = 0,
        )
        _pathStatuses[path] = recovered
        return recovered
    }

    /** All path statuses, indexed by address. */
    val pathStatuses: Map<String, PathStatus> get() = _pathStatuses

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

   fun assocId(host: String, port: Int): Long =
        (host.hashCode().toLong() shl 32) xor port.toLong()

    // ── Server side ──────────────────────────────────────────────────────

    /** Begin listening — server stays CLOSED per RFC 4960 §5.2.2 cookie mechanism. */
    suspend fun bind(port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        wire.bind(port)
        val id = port.toLong()
        associations[id] = SctpState.CLOSED
        return SctpAssociation(associationId = id, state = SctpState.CLOSED)
    }

    /**
     * Server handles incoming COOKIE_ECHO: validates cookie, responds with
     * COOKIE_ACK, transitions CLOSED → ESTABLISHED (RFC 4960 §5.2.2 step 5).
     */
    suspend fun handleCookieEcho(associationId: Long, chunk: SctpCookieEchoChunk): SctpState {
        val current = associations[associationId] ?: error("Unknown association: $associationId")
        check(current == SctpState.CLOSED) { "Expected CLOSED, got $current" }
        // In a real impl: validate the cookie here
        associations[associationId] = SctpState.ESTABLISHED
        return SctpState.ESTABLISHED
    }

    // ── Client side (4-way handshake) ────────────────────────────────────

    /**
     * Initiate connection — sends INIT, enters COOKIE_WAIT (RFC 4960 §5.2.1 step 2).
     * Caller must progress through [handleInitAck] → [handleCookieAck].
     */
    suspend fun connect(host: String, port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        val id = assocId(host, port)
        associations[id] = SctpState.COOKIE_WAIT
        return SctpAssociation(associationId = id, state = SctpState.COOKIE_WAIT)
    }

    /**
     * Client receives INIT_ACK: sends COOKIE_ECHO, transitions
     * COOKIE_WAIT → COOKIE_ECHOED (RFC 4960 §5.2.1 step 4).
     */
    suspend fun handleInitAck(associationId: Long, initAck: SctpInitAckChunk, cookie: ByteArray): SctpState {
        val current = associations[associationId] ?: error("Unknown association: $associationId")
        check(current == SctpState.COOKIE_WAIT) { "Expected COOKIE_WAIT, got $current" }
        // In a real impl: send COOKIE_ECHO(cookie) on the wire
        associations[associationId] = SctpState.COOKIE_ECHOED
        return SctpState.COOKIE_ECHOED
    }

    /**
     * Client receives COOKIE_ACK: handshake complete,
     * COOKIE_ECHOED → ESTABLISHED (RFC 4960 §5.2.1 step 7).
     */
    suspend fun handleCookieAck(associationId: Long): SctpState {
        val current = associations[associationId] ?: error("Unknown association: $associationId")
        check(current == SctpState.COOKIE_ECHOED) { "Expected COOKIE_ECHOED, got $current" }
        associations[associationId] = SctpState.ESTABLISHED
        return SctpState.ESTABLISHED
    }

    // ── Teardown (RFC 4960 §9.2) ─────────────────────────────────────────

    suspend fun shutdown(associationId: Long): SctpState {
        val current = associations[associationId] ?: error("Unknown association: $associationId")
        check(current == SctpState.ESTABLISHED || current == SctpState.SHUTDOWN_PENDING) { "Expected ESTABLISHED or SHUTDOWN_PENDING, got $current" }
        associations[associationId] = SctpState.SHUTDOWN_SENT
        return SctpState.SHUTDOWN_SENT
    }

    suspend fun handleShutdown(associationId: Long, chunk: SctpShutdownChunk): SctpState {
        val current = associations[associationId] ?: error("Unknown association: $associationId")
        check(current == SctpState.ESTABLISHED || current == SctpState.SHUTDOWN_SENT) { "Expected ESTABLISHED or SHUTDOWN_SENT, got $current" }
        associations[associationId] = SctpState.SHUTDOWN_ACK_SENT
        return SctpState.SHUTDOWN_ACK_SENT
    }

    suspend fun handleShutdownAck(associationId: Long, chunk: SctpShutdownAckChunk): SctpState {
        val current = associations[associationId] ?: error("Unknown association: $associationId")
        check(current == SctpState.SHUTDOWN_SENT || current == SctpState.SHUTDOWN_ACK_SENT) { "Expected SHUTDOWN_SENT or SHUTDOWN_ACK_SENT, got $current" }
        associations[associationId] = SctpState.CLOSED
        return SctpState.CLOSED
    }

    suspend fun handleShutdownComplete(associationId: Long, chunk: SctpShutdownCompleteChunk): SctpState {
        val current = associations[associationId] ?: error("Unknown association: $associationId")
        check(current == SctpState.SHUTDOWN_ACK_SENT) { "Expected SHUTDOWN_ACK_SENT, got $current" }
        associations[associationId] = SctpState.CLOSED
        return SctpState.CLOSED
    }
}
