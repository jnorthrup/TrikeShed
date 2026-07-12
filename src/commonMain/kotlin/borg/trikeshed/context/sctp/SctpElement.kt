package borg.trikeshed.sctp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import borg.trikeshed.lib.*
import kotlinx.coroutines.channels.Channel

enum class SctpChunkType { DATA, INIT, INIT_ACK, SACK, HEARTBEAT, COOKIE_ECHO, COOKIE_ACK }

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
        val buf = ByteArray(chunkLength.toInt())
        var off = 0
        buf[off++] = SctpChunkType.SACK.ordinal.toByte()   // type
        buf[off++] = 0                                       // flags
        putUShort(buf, off, chunkLength); off += 2          // length
        putUInt(buf, off, cumulativeTsnAck); off += 4
        putUInt(buf, off, aRwnd); off += 4
        putUShort(buf, off, gapAckBlocks.size.toUShort()); off += 2
        putUShort(buf, off, duplicateTsns.size.toUShort()); off += 2
        gapAckBlocks.view.forEach { gap ->
            putUShort(buf, off, gap.start); off += 2
            putUShort(buf, off, gap.end); off += 2
        }
        duplicateTsns.view.forEach { dup ->
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

            val gaps: Series<SctpGapAckBlock> = numGaps j {
                val start = getUShort(bytes, off); off += 2
                val end   = getUShort(bytes, off); off += 2
                SctpGapAckBlock(start, end)
            }
            val dups: Series<UInt> = numDups j {
                val res = getUInt(bytes, off); off += 4
                res
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
        val buf = ByteArray(chunkLength.toInt())
        var off = 0
        buf[off++] = SctpChunkType.COOKIE_ECHO.ordinal.toByte()  // type
        buf[off++] = 0                                              // flags
        putUShort(buf, off, chunkLength); off += 2                 // length
        cookie.forEachIndexed { i, b -> buf[off + i] = b }
        return buf
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
            val totalLen = getUShort(bytes, 2).toInt()
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
        SctpChunkType.COOKIE_ACK.ordinal.toByte(),  // type
        0,                                            // flags
        0, 4,                                         // length = 4 (big-endian)
    )

    fun decode(bytes: ByteArray) {
        require(bytes.size >= CHUNK_LENGTH.toInt()) { "COOKIE_ACK too short: ${bytes.size} < $CHUNK_LENGTH" }
    }
}

// ── Primitive encoding helpers (big-endian) ─────────────────────────────────
fun putUShort(buf: ByteArray, off: Int, value: UShort) {
    val v = value.toInt()
    buf[off]     = (v shr 8).toByte()
    buf[off + 1] = v.toByte()
}
fun putUInt(buf: ByteArray, off: Int, value: UInt) {
    var v = value
    repeat(4) { i -> buf[off + 3 - i] = v.toByte(); v = v shr 8 }
}
fun getUShort(buf: ByteArray, off: Int): UShort =
    ((buf[off].toInt() and 0xFF) shl 8 or (buf[off + 1].toInt() and 0xFF)).toUShort()
fun getUInt(buf: ByteArray, off: Int): UInt {
    var v = 0u
    repeat(4) { i -> v = (v shl 8) or (buf[off + i].toInt() and 0xFF).toUInt() }
    return v
}

val SctpKey: AsyncContextKey<SctpElement> = SctpElement.Key

suspend fun openSctpElement(): SctpElement =
    SctpElement().also { it.open() }

class SctpElement(
   val streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
   val associations: MutableMap<Long, SctpState> = mutableMapOf(),
   val paths: List<String> = emptyList(),          // multi-homing: active path addresses
   val congestionControl: String = "cubic"          // cubic | hstcp | rack — deterministic only
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
}
