package borg.trikeshed.torrent

import borg.trikeshed.cursor.monotonicNanoTime
import kotlin.math.abs
import kotlin.random.Random

enum class UtpState { IDLE, SYN_SENT, SYN_ACKED, CONNECTED, FIN_SENT, CLOSED, ERROR }
enum class UtpType(val id: Int) { ST_DATA(0), FIN(1), STATE(2), RESET(3), SYN(4) }

/** BEP 29 v1: 20 bytes, all multibyte fields in network byte order. */
data class UtpHeader(
    val type: UtpType, val version: Int = 1, val connId: Int, val timestamp: Long,
    val wndSize: Long, val seqNr: Int, val ackNr: Int,
    val timestampDifference: Long = 0, val extension: Int = 0,
)

fun UtpHeader.encode(): ByteArray {
    require(version == 1 && connId in 0..65535 && seqNr in 0..65535 && ackNr in 0..65535)
    require(wndSize in 0..0xffffffffL && extension in 0..255)
    val bytes = ByteArray(20)
    bytes[0] = ((type.id shl 4) or version).toByte(); bytes[1] = extension.toByte()
    fun put16(at: Int, value: Int) { bytes[at] = (value ushr 8).toByte(); bytes[at + 1] = value.toByte() }
    fun put32(at: Int, value: Long) { repeat(4) { bytes[at + it] = (value ushr (24 - it * 8)).toByte() } }
    put16(2, connId); put32(4, timestamp); put32(8, timestampDifference); put32(12, wndSize)
    put16(16, seqNr); put16(18, ackNr)
    return bytes
}

fun decodeUtpHeader(bytes: ByteArray): UtpHeader? {
    if (bytes.size < 20 || bytes[0].toInt() and 15 != 1) return null
    val type = UtpType.entries.singleOrNull { it.id == (bytes[0].toInt() and 255) ushr 4 } ?: return null
    fun u16(at: Int) = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
    fun u32(at: Int): Long = (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[at + index].toLong() and 255) }
    return UtpHeader(type, 1, u16(2), u32(4), u32(12), u16(16), u16(18), u32(8), bytes[1].toInt() and 255)
}

data class UtpPacket(val header: UtpHeader, val payload: ByteArray = byteArrayOf(), val selectiveAck: ByteArray = byteArrayOf()) {
    fun encode(): ByteArray {
        require(selectiveAck.isEmpty() || selectiveAck.size in 4..252 && selectiveAck.size % 4 == 0)
        return header.copy(extension = if (selectiveAck.isEmpty()) 0 else 1).encode() +
            (if (selectiveAck.isEmpty()) byteArrayOf() else byteArrayOf(0, selectiveAck.size.toByte()) + selectiveAck) + payload
    }
}

fun decodeUtpPacket(bytes: ByteArray): UtpPacket? {
    if (bytes.size > 65507) return null
    val header = decodeUtpHeader(bytes) ?: return null
    var extension = header.extension; var offset = 20; var count = 0; var sack = byteArrayOf()
    while (extension != 0) {
        if (++count > 8 || offset + 2 > bytes.size) return null
        val next = bytes[offset].toInt() and 255; val length = bytes[offset + 1].toInt() and 255
        offset += 2
        if (offset + length > bytes.size) return null
        if (extension == 1) {
            if (sack.isNotEmpty() || length < 4 || length % 4 != 0) return null
            sack = bytes.copyOfRange(offset, offset + length)
        }
        offset += length; extension = next
    }
    val payload = bytes.copyOfRange(offset, bytes.size)
    if (header.type == UtpType.ST_DATA && payload.isEmpty() || header.type != UtpType.ST_DATA && payload.isNotEmpty()) return null
    return UtpPacket(header, payload, sack)
}

data class UtpLimits(
    val payloadBytes: Int = 1200, val receiveBytes: Int = 65536, val sendBytes: Int = 65536,
    val maxPackets: Int = 128, val reorderPackets: Int = 64, val maxRetransmissions: Int = 8,
    val connectTimeoutMillis: Long = 15000, val writeTimeoutMillis: Long = 60000,
    val closeTimeoutMillis: Long = 10000, val maxConnections: Int = 32, val tickMillis: Long = 10,
) {
    init {
        require(payloadBytes in 150..1400 && receiveBytes in payloadBytes..1048576 && sendBytes in payloadBytes..1048576)
        require(maxPackets in 2..1024 && reorderPackets in 1..992 && maxRetransmissions in 1..16)
        require(connectTimeoutMillis in 100..180000 && writeTimeoutMillis in 100..180000 && closeTimeoutMillis in 100..180000)
        require(maxConnections in 1..256 && tickMillis in 1..100)
    }
}

/** Serialized protocol state; the common uTP stream owns its invocation and bounded I/O channels. */
class UtpSocket(
    val connId: Int, val initiator: Boolean = true, val limits: UtpLimits = UtpLimits(),
    val clock: () -> Long = { monotonicNanoTime() / 1000 },
    initialSequence: Int = if (initiator) 1 else Random.nextInt(65536),
) {
    init { require(connId in 0..65535 && initialSequence in 0..65535) }
    val receiveId: Int = if (initiator) connId else (connId + 1) and 65535
    val sendId: Int = if (initiator) (connId + 1) and 65535 else connId
    var state = UtpState.IDLE; private set
    var failure: String? = null; private set
    var seqNr = initialSequence; private set
    var ackNr = 0; private set
    var remoteWindow = limits.receiveBytes.toLong(); private set
    var congestionWindow = (limits.payloadBytes * 2).toLong(); private set
    var flightBytes = 0; private set
    var retransmissions = 0; private set
    var rtoMicros = 1_000_000L; private set
    var receivedEof = false; private set
    val sendIdle: Boolean get() = pending.isEmpty() && unacked.isEmpty()
    val readableBytes: Int get() = received.sumOf { it.size } - readOffset
    val receiveWindow: Int get() = limits.receiveBytes - bufferedReceive

    private data class Sent(val sequence: Int, val type: UtpType, val bytes: ByteArray, var sentAt: Long, var transmissions: Int = 0)
    private data class Delay(val second: Long, var minimum: Long)
    private val unacked = linkedMapOf<Int, Sent>()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private val received = ArrayDeque<ByteArray>()
    private var readOffset = 0
    private var bufferedReceive = 0
    private val reorder = mutableMapOf<Int, UtpPacket>()
    private val outgoing = ArrayDeque<UtpPacket>()
    private var remoteAck = (initialSequence - 1) and 65535
    private var replyMicros = 0L
    private var rtt = 0L
    private var rttVariance = 0L
    private val delays = ArrayDeque<Delay>()
    private var duplicateAcks = 0
    private var fastResendAck: Int? = null
    private var finRequested = false
    private var lastProbe = 0L

    fun connect() {
        check(initiator && state == UtpState.IDLE)
        state = UtpState.SYN_SENT; transmitNew(UtpType.SYN, byteArrayOf())
    }

    fun accept(packet: UtpPacket) {
        check(!initiator && state == UtpState.IDLE)
        require(packet.header.type == UtpType.SYN && packet.header.connId == connId)
        ackNr = packet.header.seqNr; remoteWindow = packet.header.wndSize
        replyMicros = (clock() - packet.header.timestamp) and 0xffffffffL
        state = UtpState.SYN_ACKED; acknowledge()
    }

    /** Admits at most the bounded send capacity; sendIdle denotes acknowledgement of all admitted bytes. */
    fun send(bytes: ByteArray): Int {
        check(state == UtpState.CONNECTED || state == UtpState.SYN_ACKED) { "uTP is not established" }
        check(!finRequested) { "uTP write admission is closed" }
        val admitted = minOf(bytes.size, limits.sendBytes - pendingBytes - flightBytes)
        var offset = 0
        while (offset < admitted) {
            val length = minOf(limits.payloadBytes, admitted - offset)
            pending.addLast(bytes.copyOfRange(offset, offset + length)); pendingBytes += length; offset += length
        }
        pump(); return admitted
    }

    /** null means wait; empty means ordered EOF. Reading advertises the reopened receive window. */
    fun read(maxBytes: Int): ByteArray? {
        require(maxBytes in 1..1048576)
        if (received.isEmpty()) return if (receivedEof || state == UtpState.CLOSED) byteArrayOf() else null
        val result = ByteArray(minOf(maxBytes, readableBytes)); var offset = 0
        while (offset < result.size) {
            val chunk = received.first(); val length = minOf(chunk.size - readOffset, result.size - offset)
            chunk.copyInto(result, offset, readOffset, readOffset + length)
            readOffset += length; offset += length; bufferedReceive -= length
            if (readOffset == chunk.size) { received.removeFirst(); readOffset = 0 }
        }
        acknowledge(); return result
    }

    fun receive(packet: UtpPacket) {
        val header = packet.header
        if (state == UtpState.ERROR || state == UtpState.CLOSED) return
        if (header.type == UtpType.SYN) {
            if (!initiator && header.connId == connId && state == UtpState.SYN_ACKED && header.seqNr == ackNr) acknowledge()
            return
        }
        if (header.connId != receiveId) return
        if (header.type == UtpType.RESET) { abort("Remote uTP reset"); return }
        if (state == UtpState.SYN_SENT && (header.type != UtpType.STATE || header.ackNr != ((seqNr - 1) and 65535))) return
        val advance = distance(remoteAck, header.ackNr)
        val oldAck = advance >= 32768
        if (!oldAck && advance > distance(remoteAck, (seqNr - 1) and 65535)) return
        val now = clock(); replyMicros = (now - header.timestamp) and 0xffffffffL
        val previousWindow = remoteWindow; remoteWindow = header.wndSize
        if (state == UtpState.SYN_SENT) {
            // STATE consumes no sequence number. libutp's first DATA uses STATE.seq_nr.
            ackNr = (header.seqNr - 1) and 65535; state = UtpState.CONNECTED
        } else if (state == UtpState.SYN_ACKED) state = UtpState.CONNECTED
        if (!oldAck) applyAck(header, packet.selectiveAck, now, previousWindow)
        when (header.type) {
            UtpType.ST_DATA, UtpType.FIN -> {
                val ahead = distance(ackNr, header.seqNr)
                if (!receivedEof && ahead in 1..limits.reorderPackets && !reorder.containsKey(header.seqNr) && packet.payload.size <= receiveWindow) {
                    bufferedReceive += packet.payload.size; reorder[header.seqNr] = packet
                    while (true) {
                        val next = (ackNr + 1) and 65535; val ordered = reorder.remove(next) ?: break
                        ackNr = next
                        if (ordered.header.type == UtpType.FIN) {
                            receivedEof = true
                            for (discarded in reorder.values) bufferedReceive -= discarded.payload.size
                            reorder.clear(); break
                        }
                        received.addLast(ordered.payload)
                    }
                }
                acknowledge()
            }
            else -> Unit
        }
        pump()
    }

    fun close() { finRequested = true; pump() }

    fun tick() {
        if (state == UtpState.ERROR || state == UtpState.CLOSED) return
        val now = clock(); val oldest = unacked.values.firstOrNull()
        if (oldest != null && now - oldest.sentAt >= rtoMicros) {
            congestionWindow = limits.payloadBytes.toLong(); rtoMicros = (rtoMicros * 2).coerceAtMost(60_000_000)
            transmit(oldest, true)
        } else if (oldest == null && pending.isNotEmpty() && remoteWindow == 0L && now - lastProbe >= rtoMicros) {
            // A one-byte probe recovers a lost window update without unbounded queue growth.
            val bytes = pending.removeFirst(); pendingBytes--
            if (bytes.size > 1) pending.addFirst(bytes.copyOfRange(1, bytes.size))
            transmitNew(UtpType.ST_DATA, bytes.copyOf(1)); lastProbe = now
        }
        pump()
    }

    fun takePacket(): ByteArray? = outgoing.removeFirstOrNull()?.encode()

    fun abort(reason: String) {
        failure = reason; state = UtpState.ERROR
        pending.clear(); pendingBytes = 0; unacked.clear(); flightBytes = 0; outgoing.clear()
    }

    private fun pump() {
        if (state != UtpState.CONNECTED && state != UtpState.SYN_ACKED) return
        while (pending.isNotEmpty() && unacked.size < limits.maxPackets) {
            val allowance = (minOf(congestionWindow, remoteWindow) - flightBytes).coerceAtMost(limits.payloadBytes.toLong()).toInt()
            if (allowance <= 0) break
            val queued = pending.removeFirst(); val length = minOf(queued.size, allowance)
            if (length < queued.size) pending.addFirst(queued.copyOfRange(length, queued.size))
            pendingBytes -= length
            transmitNew(UtpType.ST_DATA, if (length == queued.size) queued else queued.copyOf(length))
        }
        if (finRequested && pending.isEmpty() && unacked.isEmpty()) { transmitNew(UtpType.FIN, byteArrayOf()); state = UtpState.FIN_SENT }
    }

    private fun transmitNew(type: UtpType, bytes: ByteArray) {
        val packet = Sent(seqNr, type, bytes, clock()); seqNr = (seqNr + 1) and 65535
        unacked[packet.sequence] = packet; flightBytes += bytes.size; transmit(packet, false)
    }

    private fun transmit(packet: Sent, retry: Boolean) {
        if (retry) {
            if (packet.transmissions > limits.maxRetransmissions) { abort("uTP retransmission limit"); return }
            retransmissions++; congestionWindow = maxOf(limits.payloadBytes.toLong(), congestionWindow / 2)
        }
        packet.transmissions++; packet.sentAt = clock()
        emit(UtpPacket(header(packet.type, packet.sequence, if (packet.type == UtpType.SYN) connId else sendId), packet.bytes))
    }

    private fun header(type: UtpType, sequence: Int, id: Int = sendId) = UtpHeader(type, connId = id,
        timestamp = clock() and 0xffffffffL, timestampDifference = replyMicros, wndSize = receiveWindow.toLong(), seqNr = sequence, ackNr = ackNr)

    private fun acknowledge() {
        if (state == UtpState.IDLE || state == UtpState.ERROR) return
        val mask = if (reorder.isEmpty()) byteArrayOf() else ByteArray(((limits.reorderPackets + 31) / 32) * 4)
        for (sequence in reorder.keys) {
            val bit = distance(ackNr, sequence) - 2
            if (bit >= 0 && bit < mask.size * 8) mask[bit / 8] = (mask[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        emit(UtpPacket(header(UtpType.STATE, seqNr), selectiveAck = mask))
    }

    private fun emit(packet: UtpPacket) {
        if (packet.header.type == UtpType.STATE && outgoing.lastOrNull()?.header?.type == UtpType.STATE) outgoing.removeLast()
        check(outgoing.size < limits.maxPackets + 8) { "uTP output must be drained by its owner" }
        outgoing.addLast(packet)
    }

    private fun applyAck(header: UtpHeader, sack: ByteArray, now: Long, previousWindow: Long) {
        val advance = distance(remoteAck, header.ackNr); val acknowledged = mutableSetOf<Int>()
        for (sequence in unacked.keys) if (advance > 0 && distance(remoteAck, sequence) in 1..advance) acknowledged += sequence
        for (index in sack.indices) for (bit in 0..7) if (sack[index].toInt() and (1 shl bit) != 0) {
            val sequence = (header.ackNr + 2 + index * 8 + bit) and 65535
            if (unacked.containsKey(sequence)) acknowledged += sequence
        }
        var bytesAcked = 0; var selectivePastOldest = 0; val oldestSequence = unacked.keys.firstOrNull()
        for (sequence in acknowledged) {
            val packet = unacked.remove(sequence) ?: continue
            bytesAcked += packet.bytes.size; flightBytes -= packet.bytes.size
            if (oldestSequence != null && distance(oldestSequence, sequence) in 1..32767) selectivePastOldest++
            if (packet.transmissions == 1) {
                val sample = maxOf(1, now - packet.sentAt)
                if (rtt == 0L) { rtt = sample; rttVariance = sample / 2 }
                else { rttVariance += (abs(rtt - sample) - rttVariance) / 4; rtt += (sample - rtt) / 8 }
                rtoMicros = (rtt + 4 * rttVariance).coerceIn(500_000, 60_000_000)
            }
            if (packet.type == UtpType.FIN) state = UtpState.CLOSED
        }
        if (advance > 0) { remoteAck = header.ackNr; duplicateAcks = 0; fastResendAck = null }
        else if (previousWindow == header.wndSize && unacked.isNotEmpty()) duplicateAcks++
        if (bytesAcked > 0) adjustWindow(header.timestampDifference, bytesAcked, now)
        val oldest = unacked.values.firstOrNull()
        if (oldest != null && (duplicateAcks >= 3 || selectivePastOldest >= 3) && fastResendAck != remoteAck) {
            fastResendAck = remoteAck; transmit(oldest, true)
        }
    }

    private fun adjustWindow(delay: Long, bytesAcked: Int, now: Long) {
        if (delay == 0L) return
        val second = now / 1_000_000
        if (delays.lastOrNull()?.second == second) delays.last().minimum = minOf(delays.last().minimum, delay)
        else delays.addLast(Delay(second, delay))
        while (delays.isNotEmpty() && second - delays.first().second >= 120) delays.removeFirst()
        val base = delays.minOf { it.minimum }; val queuing = maxOf(0, delay - base)
        val gain = limits.payloadBytes.toDouble() * bytesAcked / congestionWindow.coerceAtLeast(1) * (100_000 - queuing) / 100_000.0
        congestionWindow = (congestionWindow + gain.toLong()).coerceIn(limits.payloadBytes.toLong(), limits.sendBytes.toLong())
    }

    companion object {
        fun distance(from: Int, to: Int): Int = (to - from) and 65535
        fun deriveConnId(infoHash: ByteArray, secret: ByteArray, initiator: Boolean): Int =
            Sha1.digest(if (initiator) infoHash + secret else secret + infoHash).let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
    }
}
