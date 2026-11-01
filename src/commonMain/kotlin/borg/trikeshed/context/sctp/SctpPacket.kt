package borg.trikeshed.sctp

import borg.trikeshed.couch.isam.Crc32C
import kotlin.jvm.JvmInline

/*
 * SCTP packet codec (RFC 9260 §3) over one ByteArray per packet. Every field is network order (big-endian) except the
 * CRC32c, which is stored as the kernel stores it (least significant byte first). Writers append in place and readers
 * take offsets: no object per chunk, no ByteBuffer, so the calls inline and stay monomorphic.
 */

/** A receiver's own verification tag (RFC 9260 §8.5): random, nonzero, the receiver's key for the association. */
typealias VerificationTag = Int

/** Transmission sequence number (RFC 9260 §3.3.1), compared in serial-number arithmetic modulo 2^32. */
typealias Tsn = Int

/** Payload protocol identifier (RFC 9260 §3.3.1): names the framing of the user data, e.g. an HTX version. */
typealias Ppid = Int

/**
 * The 64-bit connection number: the local verification tag in the high half, the peer's in the low half. Both are
 * random and nonzero, so the pair names one association without a kernel object.
 */
@JvmInline
value class ConnectionNumber(val bits: Long) {
    constructor(local: VerificationTag, peer: VerificationTag) : this((local.toLong() shl 32) or (peer.toLong() and 0xffffffffL))
    val localTag: VerificationTag get() = (bits ushr 32).toInt()
    val peerTag: VerificationTag get() = bits.toInt()
}

/** a - b in serial-number arithmetic: positive when [a] is after [b]. */
inline fun tsnDistance(a: Tsn, b: Tsn): Int = a - b

inline fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xff
inline fun ByteArray.net16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
inline fun ByteArray.net32(at: Int): Int = (u8(at) shl 24) or (u8(at + 1) shl 16) or (u8(at + 2) shl 8) or u8(at + 3)
inline fun ByteArray.put8(at: Int, v: Int) { this[at] = v.toByte() }
inline fun ByteArray.putNet16(at: Int, v: Int) { this[at] = (v ushr 8).toByte(); this[at + 1] = v.toByte() }
inline fun ByteArray.putNet32(at: Int, v: Int) {
    this[at] = (v ushr 24).toByte(); this[at + 1] = (v ushr 16).toByte(); this[at + 2] = (v ushr 8).toByte(); this[at + 3] = v.toByte()
}

/** Chunk type codes (RFC 9260 §3.2) as the wire carries them. */
object ChunkType {
    const val DATA = 0
    const val INIT = 1
    const val INIT_ACK = 2
    const val SACK = 3
    const val HEARTBEAT = 4
    const val HEARTBEAT_ACK = 5
    const val ABORT = 6
    const val SHUTDOWN = 7
    const val SHUTDOWN_ACK = 8
    const val ERROR = 9
    const val COOKIE_ECHO = 10
    const val COOKIE_ACK = 11
    const val SHUTDOWN_COMPLETE = 14
}

/** DATA chunk flags (RFC 9260 §3.3.1): Unordered, Beginning and Ending fragment. */
object DataFlags {
    const val E = 1
    const val B = 2
    const val U = 4
    /** One whole message, ordered. */
    const val WHOLE = B or E
}

/** Common header: source port, destination port, verification tag, checksum. */
const val SCTP_HEADER = 12

/** DATA chunk header: type, flags, length, TSN, stream, stream sequence number, PPID. */
const val DATA_HEADER = 16

/** INIT and INIT ACK fixed part: chunk header plus tag, a_rwnd, streams, initial TSN. */
const val INIT_FIXED = 20

/** State Cookie parameter type (RFC 9260 §3.3.3.1). */
const val PARAM_STATE_COOKIE = 7

/** The CRC32c of a whole packet with its checksum field read as zero. */
fun sctpChecksum(packet: ByteArray, length: Int): Int {
    val saved = packet.net32(8)
    packet.putNet32(8, 0)
    val crc = Crc32C.compute(packet, 0, length)
    packet.putNet32(8, saved)
    return crc
}

/** True when the stored checksum (least significant byte first) matches the packet. */
fun sctpChecksumValid(packet: ByteArray, length: Int): Boolean {
    if (length < SCTP_HEADER) return false
    val stored = packet.u8(8) or (packet.u8(9) shl 8) or (packet.u8(10) shl 16) or (packet.u8(11) shl 24)
    return stored == sctpChecksum(packet, length)
}

inline fun ByteArray.sourcePort(): Int = net16(0)
inline fun ByteArray.destinationPort(): Int = net16(2)
inline fun ByteArray.verificationTag(): VerificationTag = net32(4)

/** Chunk accessors at a chunk offset [at]. */
inline fun ByteArray.chunkType(at: Int): Int = u8(at)
inline fun ByteArray.chunkFlags(at: Int): Int = u8(at + 1)
inline fun ByteArray.chunkLength(at: Int): Int = net16(at + 2)
inline fun ByteArray.dataTsn(at: Int): Tsn = net32(at + 4)
inline fun ByteArray.dataStream(at: Int): Int = net16(at + 8)
inline fun ByteArray.dataSsn(at: Int): Int = net16(at + 10)
inline fun ByteArray.dataPpid(at: Int): Ppid = net32(at + 12)
inline fun ByteArray.initTag(at: Int): VerificationTag = net32(at + 4)
inline fun ByteArray.initRwnd(at: Int): Int = net32(at + 8)
inline fun ByteArray.initOutboundStreams(at: Int): Int = net16(at + 12)
inline fun ByteArray.initInboundStreams(at: Int): Int = net16(at + 14)
inline fun ByteArray.initTsn(at: Int): Tsn = net32(at + 16)
inline fun ByteArray.sackCumulative(at: Int): Tsn = net32(at + 4)
inline fun ByteArray.sackRwnd(at: Int): Int = net32(at + 8)
inline fun ByteArray.sackGaps(at: Int): Int = net16(at + 12)
inline fun ByteArray.sackDuplicates(at: Int): Int = net16(at + 14)
/** Gap block [i]: start and end offsets from the cumulative TSN, inclusive. */
inline fun ByteArray.sackGapStart(at: Int, i: Int): Int = net16(at + 16 + 4 * i)
inline fun ByteArray.sackGapEnd(at: Int, i: Int): Int = net16(at + 18 + 4 * i)

/**
 * Each chunk of a packet: [visit] gets its type, flags, offset and length (padding excluded). A chunk whose length
 * runs past [length] ends the walk; a malformed packet yields its whole chunks only.
 */
inline fun ByteArray.forEachChunk(length: Int, visit: (type: Int, flags: Int, at: Int, chunkLength: Int) -> Unit) {
    var at = SCTP_HEADER
    while (at + 4 <= length) {
        val len = chunkLength(at)
        if (len < 4 || at + len > length) return
        visit(chunkType(at), chunkFlags(at), at, len)
        at += (len + 3) and 3.inv()
    }
}

/**
 * The State Cookie parameter inside an INIT ACK chunk at [at] of [chunkLength]: its value's offset and length, or -1
 * when absent. Parameters follow the fixed part, each padded to 4 bytes.
 */
fun ByteArray.stateCookie(at: Int, chunkLength: Int): Long {
    var p = at + INIT_FIXED
    val end = at + chunkLength
    while (p + 4 <= end) {
        val type = net16(p)
        val len = net16(p + 2)
        if (len < 4 || p + len > end) return -1L
        if (type == PARAM_STATE_COOKIE) return ((p + 4).toLong() shl 32) or (len - 4).toLong()
        p += (len + 3) and 3.inv()
    }
    return -1L
}

/**
 * One outbound packet: [begin] writes the common header, the chunk writers append, [seal] stores the checksum and
 * returns the length. The buffer is reused packet after packet; [room] says whether a chunk of that size still fits.
 */
class SctpPacketWriter(val buffer: ByteArray) {
    var length: Int = 0

    fun begin(sourcePort: Int, destinationPort: Int, tag: VerificationTag) {
        buffer.putNet16(0, sourcePort)
        buffer.putNet16(2, destinationPort)
        buffer.putNet32(4, tag)
        buffer.putNet32(8, 0)
        length = SCTP_HEADER
    }

    inline val empty: Boolean get() = length == SCTP_HEADER

    fun room(chunkLength: Int): Boolean = length + ((chunkLength + 3) and 3.inv()) <= buffer.size

    fun chunk(type: Int, flags: Int, valueLength: Int): Int {
        val at = length
        val len = 4 + valueLength
        buffer.put8(at, type)
        buffer.put8(at + 1, flags)
        buffer.putNet16(at + 2, len)
        val padded = (len + 3) and 3.inv()
        for (i in at + len until at + padded) buffer[i] = 0
        length = at + padded
        return at
    }

    fun data(tsn: Tsn, stream: Int, ssn: Int, ppid: Ppid, flags: Int, payload: ByteArray, offset: Int, count: Int) {
        val at = chunk(ChunkType.DATA, flags, DATA_HEADER - 4 + count)
        buffer.putNet32(at + 4, tsn)
        buffer.putNet16(at + 8, stream)
        buffer.putNet16(at + 10, ssn)
        buffer.putNet32(at + 12, ppid)
        payload.copyInto(buffer, at + DATA_HEADER, offset, offset + count)
    }

    /** INIT or INIT ACK; an INIT ACK carries [cookie] as its State Cookie parameter. */
    fun init(type: Int, tag: VerificationTag, rwnd: Int, outbound: Int, inbound: Int, tsn: Tsn, cookie: ByteArray? = null) {
        val cookieParam = if (cookie == null) 0 else (4 + cookie.size + 3) and 3.inv()
        val at = chunk(type, 0, INIT_FIXED - 4 + cookieParam)
        buffer.putNet32(at + 4, tag)
        buffer.putNet32(at + 8, rwnd)
        buffer.putNet16(at + 12, outbound)
        buffer.putNet16(at + 14, inbound)
        buffer.putNet32(at + 16, tsn)
        if (cookie != null) {
            val p = at + INIT_FIXED
            buffer.putNet16(p, PARAM_STATE_COOKIE)
            buffer.putNet16(p + 2, 4 + cookie.size)
            cookie.copyInto(buffer, p + 4)
            for (i in p + 4 + cookie.size until p + cookieParam) buffer[i] = 0
            // The parameter's padding is part of the chunk: the chunk length covers it (RFC 9260 §3.2.1).
            buffer.putNet16(at + 2, INIT_FIXED + cookieParam)
        }
    }

    /** SACK over [gaps] start/end pairs (offsets from [cumulative]); duplicates are not reported. */
    fun sack(cumulative: Tsn, rwnd: Int, gaps: IntArray, gapCount: Int) {
        val at = chunk(ChunkType.SACK, 0, 12 + 4 * gapCount)
        buffer.putNet32(at + 4, cumulative)
        buffer.putNet32(at + 8, rwnd)
        buffer.putNet16(at + 12, gapCount)
        buffer.putNet16(at + 14, 0)
        for (i in 0 until gapCount) {
            buffer.putNet16(at + 16 + 4 * i, gaps[2 * i])
            buffer.putNet16(at + 18 + 4 * i, gaps[2 * i + 1])
        }
    }

    fun cookieEcho(cookie: ByteArray, offset: Int, count: Int) {
        val at = chunk(ChunkType.COOKIE_ECHO, 0, count)
        cookie.copyInto(buffer, at + 4, offset, offset + count)
    }

    fun shutdown(cumulative: Tsn) {
        val at = chunk(ChunkType.SHUTDOWN, 0, 4)
        buffer.putNet32(at + 4, cumulative)
    }

    /** A chunk with no value: COOKIE ACK, SHUTDOWN ACK, SHUTDOWN COMPLETE, ABORT. */
    fun bare(type: Int, flags: Int = 0) { chunk(type, flags, 0) }

    fun seal(): Int {
        val crc = sctpChecksum(buffer, length)
        buffer[8] = crc.toByte(); buffer[9] = (crc ushr 8).toByte(); buffer[10] = (crc ushr 16).toByte(); buffer[11] = (crc ushr 24).toByte()
        return length
    }
}
