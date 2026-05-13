package borg.trikeshed.sctp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import borg.trikeshed.lib.*
import borg.trikeshed.tls.TlsEngine
import kotlinx.coroutines.channels.Channel

// ── Enums (preserved — test assertions pin exact sizes) ───────────────

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

// ── Sealed error hierarchy — Gap 1: mirror QuicErrorException categories ──

sealed class SctpError(message: CharSequence) : RuntimeException(message.toString()) {
    class BindFailed(details: CharSequence) : SctpError(details)
    class ConnectFailed(details: CharSequence) : SctpError(details)
    class Closed : SctpError("SCTP element is closed")

    /** Protocol-level violations (invalid chunks, TSN ordering, state machine). */
    sealed class Protocol(details: CharSequence) : SctpError(details) {
        class InvalidChunk(type: SctpChunkType, reason: CharSequence) :
            Protocol("Invalid chunk $type: $reason")
        class TsnViolation(expected: UInt, got: UInt) :
            Protocol("TSN violation: expected $expected, got $got")
        class InvalidStateTransition(expected: SctpState, actual: SctpState) :
            Protocol("Invalid state transition: expected $expected, got $actual")
    }

    /** Transport-layer failures (bind, path unreachable, no paths left). */
    sealed class Transport(details: CharSequence) : SctpError(details) {
        class BindFailed(details: CharSequence) : Transport(details)
        class ConnectFailed(details: CharSequence) : Transport(details)
        class PathFailure(address: CharSequence, failures: Int) :
            Transport("Path $address failed after $failures heartbeat(s)")
        class NoAvailablePaths : Transport("No active paths for multi-homing failover")
    }

    /** Crypto/DTLS wrapping failures — mirrors TlsElement error surface. */
    sealed class Crypto(details: CharSequence) : SctpError(details) {
        class WrapFailed(cause: Throwable?) :
            Crypto("TLS wrap failed" + (cause?.let { ": ${it.message}" } ?: ""))
        class UnwrapFailed(cause: Throwable?) :
            Crypto("TLS unwrap failed" + (cause?.let { ": ${it.message}" } ?: ""))
        class HandshakeFailed(cause: Throwable?) :
            Crypto("TLS handshake failed" + (cause?.let { ": ${it.message}" } ?: ""))
    }
}

// ── SctpConfig — Gap 2: replace ad-hoc ctor params with typed config ──

/**
 * SCTP association configuration.
 *
 * Mirrors QuicConfig — all fields have sensible defaults, and the config
 * object is immutable so the same algebra can be shared across associations.
 */
data class SctpConfig(
    val maxInboundStreams: UShort = 10u,
    val maxOutboundStreams: UShort = 10u,
    val initialRwnd: UInt = 16384u,
    val heartbeatIntervalMs: Long = 30_000L,
    val pathMaxRetries: Int = 5,
    val congestionAlgorithm: CongestionAlgorithm = CongestionAlgorithm.CUBIC,
    /**
     * Optional TLS engine for DTLS-style wrapping of SCTP payloads.
     * When null, data is sent in cleartext (standard SCTP behavior).
     * When set, all user data passes through [TlsEngine.wrap]/[TlsEngine.unwrap].
     */
    val tlsEngine: TlsEngine? = null,
) {
    companion object {
        fun default(): SctpConfig = SctpConfig()
    }
}

enum class CongestionAlgorithm { CUBIC, HSTCP, RACK }

// ── Association algebra — Gap 3: richer internal state, public type preserved ──

/** Public association identity — kept as Join<Long, SctpState> for algebra. */
typealias SctpAssociation = Join<Long, SctpState>

fun SctpAssociation(associationId: Long, state: SctpState): SctpAssociation = associationId j state
val SctpAssociation.associationId: Long get() = a
val SctpAssociation.state: SctpState get() = b

/**
 * Full association tracking state — internal to SctpElement.
 * Mirrors the upstream kmp-ngsctp-upstream SctpAssociation with 13 fields,
 * but kept private to avoid leaking wire-level plumbing into the algebra.
 */
data class SctpAssociationState(
    val associationId: Long,
    val sctpState: SctpState = SctpState.CLOSED,
    val localTag: UInt = 0u,
    val remoteTag: UInt = 0u,
    val initialTsn: UInt = 0u,
    val nextTsn: UInt = 0u,
    val cumulativeTsnAck: UInt = 0u,
    val rwnd: UInt = 0u,
    val primaryPathIndex: Int = 0,
)

// ── Path status — Gap 6: Series-indexed for projection over mutation ──

data class PathStatus(
    val address: CharSequence,
    val state: PathState = PathState.UNKNOWN,
    val failures: Int = 0,
)

// ── Multi-homing service — Gap 4: DHT-analogous path discovery ───────

/**
 * Service discovery for SCTP multi-homing path resolution.
 * Analogous to DhtTransport in IPFS — discovers alternate transport
 * addresses for an endpoint so failover paths can be resolved at runtime
 * rather than hardcoded into the element's constructor.
 */
interface MultiHomingService {
    /** Resolve additional transport addresses for a given endpoint. */
    suspend fun resolvePaths(endpoint: CharSequence): Series<CharSequence>

    /** Report that [path] is no longer reachable. */
    suspend fun reportPathFailure(path: CharSequence)
}

// ── SCTP Chunk encoding (RFC 4960) — unchanged ────────────────────────

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

// ── SACK chunk (RFC 4960 §3.3.4) ────────────────────────────────────

/**
 * A single gap-ack block: [start, end] TSN offsets relative to cumulative TSN.
 */
typealias SctpGapAckBlock = Join<UShort, UShort>

fun SctpGapAckBlock(start: UShort, end: UShort): SctpGapAckBlock = start j end
val SctpGapAckBlock.start: UShort get() = a
val SctpGapAckBlock.end: UShort get() = b

/**
 * SCTP SACK chunk (RFC 4960 §3.3.4).
 */
data class SctpSackChunk(
    val cumulativeTsnAck: UInt,
    val aRwnd: UInt,
    val gapAckBlocks: Series<SctpGapAckBlock> = Join.emptySeriesOf(),
    val duplicateTsns: Series<UInt> = Join.emptySeriesOf(),
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

// ── COOKIE_ECHO / COOKIE_ACK chunks (RFC 4960 §3.3.10-3.3.11) ──────

/**
 * SCTP COOKIE_ECHO chunk (RFC 4960 §3.3.10).
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

// ── Primitive encoding helpers (big-endian) ─────────────────────────

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

// ── Key + factory ───────────────────────────────────────────────────

val SctpKey: AsyncContextKey<SctpElement> = SctpElement.Key

suspend fun openSctpElement(config: SctpConfig = SctpConfig.default()): SctpElement =
    SctpElement(config).also { it.open() }

// ── SctpElement — Gap 5/6/7: lifecycle overrides, TLS hooks, config ──

class SctpElement(
    val config: SctpConfig = SctpConfig.default(),
) : AsyncContextElement(), StreamTransport {
    companion object Key : AsyncContextKey<SctpElement>()

    override val key: AsyncContextKey<SctpElement>
        get() = Key

    // Internal state — no longer in constructor defaults (Gap 7)
    private val _streams: MutableMap<Int, StreamHandle> = mutableMapOf()
    private val _associations: MutableMap<Long, SctpAssociationState> = mutableMapOf()
    private val _pathStatuses: MutableMap<CharSequence, PathStatus> = mutableMapOf()

    /** Multi-homing discovery service — injectable for DHT-style path resolution (Gap 4). */
    var multiHomingService: MultiHomingService? = null

    /**
     * Series-indexed path statuses for projection-based access (Gap 6).
     * Replaces the old Map<CharSequence, PathStatus> return type so that
     * paths can be sliced and projected through `α` like any Series.
     */
    val pathStatuses: Series<PathStatus>
        get() = _pathStatuses.size j { i -> _pathStatuses.values.elementAt(i) }

    /** Current primary path — first ACTIVE, or first UNKNOWN. */
    val primaryPath: PathStatus?
        get() = _pathStatuses.values.firstOrNull { it.state == PathState.ACTIVE }
            ?: _pathStatuses.values.firstOrNull { it.state == PathState.UNKNOWN }

    /**
     * Mark [failedPath] as INACTIVE and return the next available ACTIVE path
     * for failover. Returns null if no paths remain.
     */
    fun failover(failedPath: CharSequence): PathStatus? {
        val current = _pathStatuses[failedPath] ?: return primaryPath
        _pathStatuses[failedPath] = current.copy(
            state = PathState.INACTIVE,
            failures = current.failures + 1,
        )
        return primaryPath
    }

    /** Mark [path] as ACTIVE (recovery after successful heartbeat probe). */
    fun recoverPath(path: CharSequence): PathStatus {
        val current = _pathStatuses[path] ?: PathStatus(address = path)
        val recovered = current.copy(
            state = PathState.ACTIVE,
            failures = 0,
        )
        _pathStatuses[path] = recovered
        return recovered
    }

    // ── Lifecycle (Gap 7) ─────────────────────────────────────────

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            config.tlsEngine?.close()
        }
        super.close()
    }

    // ── StreamTransport ───────────────────────────────────────────

    override suspend fun openStream(): StreamHandle {
        requireState(ElementState.OPEN)
        val streamId = (_streams.keys.maxOrNull() ?: -1) + 1
        val streamHandle = StreamHandle(
            id = streamId,
            send = Channel(Channel.BUFFERED),
            recv = Channel(Channel.BUFFERED),
        )
        _streams[streamId] = streamHandle
        return streamHandle
    }

    override val activeStreams: Int get() = _streams.size

    // ── TLS wrapping hooks (Gap 5) ────────────────────────────────

    /**
     * Wrap outbound SCTP user data through the optional TLS engine.
     * If no [TlsEngine] is configured in [SctpConfig], data passes through unchanged.
     * Mirrors TlsElement.wrap() — the algebra is the same: plain -> encrypted.
     */
    suspend fun wrap(plain: ByteArray): ByteArray {
        check(state.isAtLeast(ElementState.OPEN)) { "SctpElement not open" }
        return config.tlsEngine?.wrap(plain) ?: plain
    }

    /**
     * Unwrap inbound SCTP user data through the optional TLS engine.
     * Mirrors TlsElement.unwrap().
     */
    suspend fun unwrap(encrypted: ByteArray): ByteArray {
        check(state.isAtLeast(ElementState.OPEN)) { "SctpElement not open" }
        return config.tlsEngine?.unwrap(encrypted) ?: encrypted
    }

    // ── Association ID factory ────────────────────────────────────

    fun assocId(host: CharSequence, port: Int): Long =
        (host.hashCode().toLong() shl 32) xor port.toLong()

    // ── Server side ───────────────────────────────────────────────

    /** Begin listening — server stays CLOSED per RFC 4960 §5.2.2 cookie mechanism. */
    suspend fun bind(port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        val id = port.toLong()
        _associations[id] = SctpAssociationState(
            associationId = id,
            sctpState = SctpState.CLOSED,
            rwnd = config.initialRwnd,
        )
        return SctpAssociation(associationId = id, state = SctpState.CLOSED)
    }

    /**
     * Server handles incoming COOKIE_ECHO: validates cookie, responds with
     * COOKIE_ACK, transitions CLOSED → ESTABLISHED (RFC 4960 §5.2.2 step 5).
     */
    suspend fun handleCookieEcho(associationId: Long, chunk: SctpCookieEchoChunk): SctpState {
        val current = _associations[associationId] ?: error("Unknown association: $associationId")
        check(current.sctpState == SctpState.CLOSED) { "Expected CLOSED, got ${current.sctpState}" }
        _associations[associationId] = current.copy(sctpState = SctpState.ESTABLISHED)
        return SctpState.ESTABLISHED
    }

    // ── Client side (4-way handshake) ─────────────────────────────

    /**
     * Initiate connection — sends INIT, enters COOKIE_WAIT (RFC 4960 §5.2.1 step 2).
     * Caller must progress through [handleInitAck] → [handleCookieAck].
     */
    suspend fun connect(host: CharSequence, port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        val id = assocId(host, port)
        _associations[id] = SctpAssociationState(
            associationId = id,
            sctpState = SctpState.COOKIE_WAIT,
            rwnd = config.initialRwnd,
        )
        return SctpAssociation(associationId = id, state = SctpState.COOKIE_WAIT)
    }

    /**
     * Client receives INIT_ACK: sends COOKIE_ECHO, transitions
     * COOKIE_WAIT → COOKIE_ECHOED (RFC 4960 §5.2.1 step 4).
     */
    suspend fun handleInitAck(associationId: Long, initAck: SctpInitAckChunk, cookie: ByteArray): SctpState {
        val current = _associations[associationId] ?: error("Unknown association: $associationId")
        check(current.sctpState == SctpState.COOKIE_WAIT) { "Expected COOKIE_WAIT, got ${current.sctpState}" }
        _associations[associationId] = current.copy(sctpState = SctpState.COOKIE_ECHOED)
        return SctpState.COOKIE_ECHOED
    }

    /**
     * Client receives COOKIE_ACK: handshake complete,
     * COOKIE_ECHOED → ESTABLISHED (RFC 4960 §5.2.1 step 7).
     */
    suspend fun handleCookieAck(associationId: Long): SctpState {
        val current = _associations[associationId] ?: error("Unknown association: $associationId")
        check(current.sctpState == SctpState.COOKIE_ECHOED) { "Expected COOKIE_ECHOED, got ${current.sctpState}" }
        _associations[associationId] = current.copy(sctpState = SctpState.ESTABLISHED)
        return SctpState.ESTABLISHED
    }
}

// ── Free error classification functions ─────────────────────────────

fun isBindError(e: SctpError): Boolean = e is SctpError.BindFailed

fun isConnectError(e: SctpError): Boolean = e is SctpError.ConnectFailed

fun isClosedError(e: SctpError): Boolean = e is SctpError.Closed
