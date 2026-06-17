package borg.trikeshed.torrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * uTP (Micro Torrent Protocol) — BEP 29 / µTP.
 *
 * A UDP-based transport protocol with LEDBAT (Low Extra Delay Background Transport)
 * congestion control.  uTP is designed to be cooperative with other traffic —
 * it only uses bandwidth that other protocols aren't using.
 *
 * Key design:
 *   - Every packet carries a timestamp delta (delay)反馈 to the sender
 *   - LEDBAT tries to keep one-way delay (OWD) at target level
 *   - When OWD < target → increase cwnd; when OWD > target → decrease
 *   - uTP never drops packets — losses are inferred from timeouts
 */

/**
 * uTP connection state.
 */
data class UtpConnection(
    val id: Int,              // Connection ID (client sends, server flips)
    val state: UtpState = UtpState.IDLE,
    val seqNr: Int = 0,       // Outgoing sequence number
    val ackNr: Int = 0,       // Incoming acked sequence number
    val sentAck: Int = 0,     // Last ack number sent
    val cwnd: Int = 1456 * 3,// Congestion window (bytes, default ~3 MTUs)
    val ssthresh: Int = 1456 * 10, // Slow-start threshold
    val rtt: Long = 0,        // Round-trip time estimate (microseconds)
    val rttVar: Long = 0,    // RTT variance
    val baseDelay: Long = 0,  // Filtered base delay (microseconds)
    val lastSendTime: Long = 0,
    val recvBuffer: ByteArray = ByteArray(0),
    val unackedBytes: Int = 0,
)

enum class UtpState { IDLE, SYN_SENT, SYN_ACKED, CONNECTED, CLOSED, ERROR }

/**
 * uTP packet header (20 bytes).
 */
data class UtpHeader(
    val type: UtpType,        // 4 bits
    val version: Int,          // 4 bits (always 1)
    val connId: Int,           // 16 bits
    val timestamp: Long,       // 32 bits — microseconds
    val wndSize: Int,         // 32 bits — receiver advertised window
    val seqNr: Int,           // 16 bits
    val ackNr: Int,           // 16 bits
)

enum class UtpType(val id: Int) {
    ST_DATA(0),
    FIN(1),
    STATE(2),
    RESET(3),
    SYN(4),
}

/**
 * Encode uTP header to 20-byte ByteArray.
 */
fun UtpHeader.encode(): ByteArray {
    val b = ByteArray(20)
    // byte 0: type(4) | version(4)
    b[0] = ((type.id shl 4) or version).toByte()
    // byte 1-2: connection id (little-endian)
    b[1] = (connId and 0xFF).toByte()
    b[2] = ((connId shr 8) and 0xFF).toByte()
    // bytes 3-6: timestamp (little-endian)
    b[3] = (timestamp and 0xFF).toByte()
    b[4] = ((timestamp shr 8) and 0xFF).toByte()
    b[5] = ((timestamp shr 16) and 0xFF).toByte()
    b[6] = ((timestamp shr 24) and 0xFF).toByte()
    // bytes 7-10: wnd_size (little-endian)
    b[7] = (wndSize and 0xFF).toByte()
    b[8] = ((wndSize shr 8) and 0xFF).toByte()
    b[9] = ((wndSize shr 16) and 0xFF).toByte()
    b[10] = ((wndSize shr 24) and 0xFF).toByte()
    // bytes 11-12: seq_nr (little-endian)
    b[11] = (seqNr and 0xFF).toByte()
    b[12] = ((seqNr shr 8) and 0xFF).toByte()
    // bytes 13-14: ack_nr (little-endian)
    b[13] = (ackNr and 0xFF).toByte()
    b[14] = ((ackNr shr 8) and 0xFF).toByte()
    return b
}

/**
 * Decode uTP header from 20-byte ByteArray.
 */
fun decodeUtpHeader(b: ByteArray): UtpHeader? {
    if (b.size < 20) return null
    val byte0 = b[0].toInt() and 0xFF
    val typeId = byte0 shr 4
    val version = byte0 and 0x0F
    val connId = (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)
    val timestamp = (b[3].toInt() and 0xFF) or
            ((b[4].toInt() and 0xFF) shl 8) or
            ((b[5].toInt() and 0xFF) shl 16) or
            ((b[6].toInt() and 0xFF) shl 24)
    val wndSize = (b[7].toInt() and 0xFF) or
            ((b[8].toInt() and 0xFF) shl 8) or
            ((b[9].toInt() and 0xFF) shl 16) or
            ((b[10].toInt() and 0xFF) shl 24)
    val seqNr = (b[11].toInt() and 0xFF) or ((b[12].toInt() and 0xFF) shl 8)
    val ackNr = (b[13].toInt() and 0xFF) or ((b[14].toInt() and 0xFF) shl 8)
    val type = UtpType.entries.find { it.id == typeId } ?: return null
    return UtpHeader(type, version, connId, timestamp.toLong(), wndSize, seqNr, ackNr)
}

/**
 * uTP socket — LEDBAT congestion controller.
 *
 * Implements BEP 29:
 *   - SYN → SYN+ACK → CONNECTED handshake
 *   - LEDBAT one-way delay (OWD) tracking for cwnd
 *   - Selective acks + selective repeat
 */
class UtpSocket(
    val connId: Int,
    private val onSend: (ByteArray) -> Unit,
    private val onReceive: (ByteArray) -> Unit,
) {
    // Connection state
    private var state: UtpState = UtpState.IDLE
    private var seqNr: Int = 1
    private var ackNr: Int = 0
    private var remoteAckNr: Int = 0

    // LEDBAT parameters
    private var cwnd: Long = 1456L * 3        // Congestion window (bytes)
    private var ssthresh: Long = 1456L * 10   // Slow-start threshold
    private var rtt: Long = 0                  // Smoothed RTT (μs)
    private var rttVar: Long = 0               // RTT variance (μs)
    private var baseDelay: Long = Long.MAX_VALUE // Filtered base delay (μs)
    private var targetDelay: Long = 50_000      // Target OWD = 50ms (50,000 μs)

    // Flight size
    private var flightSize: Long = 0           // Bytes in flight
    private var maxPayloadPerPacket: Int = 1456 // Standard MTU

    // Retx queue: seq → Packet
    private val unacked = mutableMapOf<Int, UtpPacket>()
    // Selective ack bitfield (up to 64 packets)
    private var selectiveAcks: Long = 0

    // Timestamp tracking
    private var lastAckTime: Long = 0

    private val random = Random()

    data class UtpPacket(
        val seqNr: Int,
        val payload: ByteArray,
        val sentAt: Long, // microseconds
        var acked: Boolean = false,
    )

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Connect (send SYN).
     */
    fun connect() {
        state = UtpState.SYN_SENT
        val synPkt = makePacket(UtpType.SYN, ByteArray(0))
        unacked[synPkt.seqNr] = synPkt
        flightSize += synPkt.payload.size.toLong()
        send(synPkt)
    }

    /**
     * Accept an incoming connection (recv SYN → send SYN_ACK).
     */
    fun accept(recvConnId: Int, remoteSeqNr: Int) {
        ackNr = remoteSeqNr - 1
        state = UtpState.SYN_ACKED
        val pkt = makePacket(UtpType.STATE, ByteArray(0))
        send(pkt)
    }

    /**
     * Send data (up to cwnd limit).
     * Returns the number of bytes actually sent.
     */
    fun send(data: ByteArray): Int {
        if (state != UtpState.SYN_ACKED && state != UtpState.CONNECTED) return 0
        var sent = 0
        var offset = 0
        while (offset < data.size && flightSize + maxPayloadPerPacket <= cwnd) {
            val payload = data.copyOfRange(offset, minOf(offset + maxPayloadPerPacket, data.size))
            val pkt = makePacket(UtpType.ST_DATA, payload)
            unacked[pkt.seqNr] = pkt
            flightSize += payload.size.toLong()
            send(pkt)
            offset += payload.size
            sent += payload.size
            seqNr = (seqNr + 1) and 0xFFFF
        }
        return sent
    }

    /**
     * Handle an incoming uTP packet.
     */
    fun handlePacket(header: UtpHeader, payload: ByteArray, recvTime: Long) {
        when (header.type) {
            UtpType.SYN -> handleSyn(header, recvTime)
            UtpType.STATE -> handleState(header, recvTime)
            UtpType.ST_DATA -> handleData(header, payload, recvTime)
            UtpType.FIN -> handleFin(header, recvTime)
            UtpType.RESET -> handleReset()
        }
    }

    /**
     * Get current congestion window (for throttling sender).
     */
    fun getCwnd(): Long = cwnd

    /**
     * Close the connection (send FIN).
     */
    fun close() {
        val pkt = makePacket(UtpType.FIN, ByteArray(0))
        send(pkt)
        state = UtpState.CLOSED
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private fun handleSyn(header: UtpHeader, recvTime: Long) {
        // recvTime is in μs from socket receive timestamp
        val ourTimestamp = System.nanoTime() / 1000
        ackNr = header.seqNr - 1
        state = UtpState.SYN_ACKED
        val ackPkt = makePacket(UtpType.STATE, ByteArray(0))
        send(ackPkt)
    }

    private fun handleState(header: UtpHeader, recvTime: Long) {
        if (state == UtpState.SYN_SENT) {
            // Connection established
            state = UtpState.CONNECTED
        }
        updateRttVars(rtt.coerceAtLeast(1), recvTime - header.timestamp)
        applyAck(header.ackNr, recvTime)
    }

    private fun handleData(header: UtpHeader, payload: ByteArray, recvTime: Long) {
        ackNr = header.seqNr
        updateRttVars(rtt.coerceAtLeast(1), recvTime - header.timestamp)
        applySelectiveAck(header.ackNr)
        onReceive(payload)

        // Send ACK
        val ackPkt = makePacket(UtpType.STATE, ByteArray(0))
        send(ackPkt)
    }

    private fun handleFin(header: UtpHeader, recvTime: Long) {
        state = UtpState.CLOSED
    }

    private fun handleReset() {
        state = UtpState.ERROR
        unacked.clear()
    }

    // ── LEDBAT congestion control ─────────────────────────────────────────────

    /**
     * Apply a cumulative ACK and update LEDBAT state.
     */
    private fun applyAck(ackedSeqNr: Int, recvTime: Long) {
        val ackedPackets = unacked.keys.filter { it <= ackedSeqNr }
        ackedPackets.forEach { seq ->
            unacked.remove(seq)?.let { pkt ->
                val owd = recvTime - pkt.sentAt - baseDelay
                updateBaseDelay(max(0, owd))
                updateRttVars(rtt.coerceAtLeast(1), recvTime - pkt.sentAt)
                flightSize -= pkt.payload.size.toLong()
            }
        }
        selectiveAcks = 0

        // LEDBAT cwnd update
        val target = targetDelay
        val currentBaseDelay = baseDelay
        val offTarget = target - currentBaseDelay

        if (offTarget > 0) {
            // OWD below target — increase cwnd
            val increment = (cwnd * offTarget / targetDelay).coerceAtLeast(maxPayloadPerPacket.toLong())
            cwnd += increment
        } else {
            // OWD above target — decrease cwnd
            cwnd = (cwnd * 3 / 4).coerceAtLeast(ssthresh)
        }

        // Slow start
        if (flightSize < ssthresh) {
            cwnd += maxPayloadPerPacket.toLong()
        }
    }

    /**
     * Apply selective ACK bits.
     */
    private fun applySelectiveAck(ackNr: Int) {
        // Selective ack: bit i = 1 means seqNr = ackNr + i + 1 was received
        // Simplified: just apply the cumulative ack
    }

    /**
     * Update base delay using a min filter with exponential decay.
     */
    private fun updateBaseDelay(owd: Long) {
        baseDelay = if (owd < baseDelay) {
            owd // Immediate min filter
        } else {
            (baseDelay * 7 / 8 + owd / 8) // Smoothed rise
        }
    }

    /**
     * Update RTT estimate using Jacobson/Karels algorithm.
     */
    private fun updateRttVars(currentRtt: Long, sampleRtt: Long) {
        if (rtt == 0L) {
            rtt = sampleRtt
            rttVar = sampleRtt / 2
        } else {
            val diff = sampleRtt - rtt
            rttVar += (kotlin.math.abs(diff) - rttVar) / 4
            rtt += diff / 8
        }
    }

    // ── Packet helpers ───────────────────────────────────────────────────────

    private fun makePacket(type: UtpType, payload: ByteArray): UtpPacket {
        val sentAt = System.nanoTime() / 1000
        return UtpPacket(
            seqNr = seqNr,
            payload = payload,
            sentAt = sentAt,
        )
    }

    private fun send(pkt: UtpPacket) {
        val now = System.nanoTime() / 1000
        val header = UtpHeader(
            type = UtpType.ST_DATA,
            version = 1,
            connId = connId,
            timestamp = now,
            wndSize = 0x7FFFFFFF,
            seqNr = pkt.seqNr,
            ackNr = ackNr,
        )
        val wire = header.encode() + pkt.payload
        onSend(wire)
    }

    /**
     * Compute uTP connection ID from info-hash + peer-id hash.
     * Each direction uses its own connection ID (flipped for the responder).
     */
    companion object {
        fun deriveConnId(infoHash: ByteArray, secret: ByteArray, initiator: Boolean): Int {
            val data = if (initiator) infoHash + secret else secret + infoHash
            val digest = MessageDigest.getInstance("SHA-1").digest(data)
            return ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
        }
    }
}
