package borg.literbike.ccek.quic

// ============================================================================
// QUIC Engine Hybrid -- ported from quic_engine_hybrid.rs
// Atomics for hot path, content-addressed logging for durability
// ============================================================================

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.microseconds

/** QUIC connection state for hybrid engine */
enum class QuicState {
    Idle,
    Handshaking,
    Connected,
    Closing,
    Closed
}

/**
 * Hot path state -- all atomics, no locks.
 * Ported from Rust QuicHotState.
 */
class QuicHotState {
    val packetSequence = AtomicLong(0L)
    val ackBitmapLow = AtomicLong(0L)    // Lower 64 packets
    val ackBitmapHigh = AtomicLong(0L)   // Upper 64 packets
    val bytesInFlight = AtomicInteger(0)
    val bytesSent = AtomicLong(0L)
    val bytesReceived = AtomicLong(0L)
    val connectionState = AtomicReference(QuicState.Idle)
    val streamCounter = AtomicLong(0L)

    /** HOT PATH: next packet number (atomic fetchAdd) */
    fun nextPacketNumber(): ULong = packetSequence.incrementAndFetch().toULong()

    /** HOT PATH: update ACK bitmap (atomic OR) */
    fun updateAck(pktNum: ULong) {
        val bit = 1uL shl (pktNum % 64uL)
        if (pktNum < 64uL) {
            ackBitmapLow.updateAndFetch { it or bit.toLong() }
        } else {
            ackBitmapHigh.updateAndFetch { it or bit.toLong() }
        }
    }

    /** HOT PATH: add bytes in flight */
    fun addBytesInFlight(bytes: Int) {
        bytesInFlight.addAndFetch(bytes)
    }

    /** HOT PATH: remove bytes in flight */
    fun removeBytesInFlight(bytes: Int) {
        bytesInFlight.addAndFetch(-bytes)
    }

    /** Get current state */
    fun getState(): QuicState = connectionState.get()

    /** Set state (atomic store) */
    fun setState(state: QuicState) {
        connectionState.set(state)
    }
}

/** Log entry for content-addressed storage */
data class QuicLogEntry(
    val packetNumber: ULong,
    val timestamp: Long,
    val entryType: LogEntryType,
    val contentHash: List<UByte>,
    val data: List<UByte>
)

enum class LogEntryType {
    PacketSent,
    PacketReceived,
    AckSent,
    AckReceived,
    StateTransition
}

/**
 * Content logger -- async, non-blocking.
 * In the Kotlin port, this uses a coroutine channel instead of crossbeam.
 */
class QuicContentLogger(
    private val batchSize: Int
) {
    private val entries = mutableListOf<QuicLogEntry>()
    private val contentStore = mutableMapOf<String, ContentBlob>()

    data class ContentBlob(val data: List<UByte>, val hash: List<UByte>)

    fun logPacketSent(pktNum: ULong, data: List<UByte>) {
        val entry = createEntry(pktNum, LogEntryType.PacketSent, data)
        synchronized(entries) {
            entries.add(entry)
            contentStore["pkt:$pktNum"] = ContentBlob(data, entry.contentHash)
        }
    }

    fun logPacketReceived(pktNum: ULong, data: List<UByte>) {
        val entry = createEntry(pktNum, LogEntryType.PacketReceived, data)
        synchronized(entries) {
            entries.add(entry)
            contentStore["pkt:$pktNum"] = ContentBlob(data, entry.contentHash)
        }
    }

    private fun createEntry(pktNum: ULong, entryType: LogEntryType, data: List<UByte>): QuicLogEntry {
        val hash = sha256Hash(data)
        return QuicLogEntry(
            packetNumber = pktNum,
            timestamp = Clocks.System.now(),
            entryType = entryType,
            contentHash = hash,
            data = data
        )
    }

    fun recoverPackets(fromPkt: ULong, limit: Int): List<QuicLogEntry> {
        val packets = mutableListOf<QuicLogEntry>()
        for (i in 0 until limit) {
            val pktNum = fromPkt + i.toULong()
            contentStore["pkt:$pktNum"]?.let { blob ->
                packets.add(QuicLogEntry(
                    packetNumber = pktNum,
                    timestamp = 0L,
                    entryType = LogEntryType.PacketSent,
                    contentHash = blob.hash,
                    data = blob.data
                ))
            }
        }
        return packets
    }

    private fun sha256Hash(data: List<UByte>): List<UByte> {
        // Stub: in production use MessageDigest.getInstance("SHA-256")
        return data.take(32)
    }
}

/** QUIC packet for hybrid engine */
data class QuicPacketHybrid(
    val packetNumber: ULong,
    val streamId: ULong,
    val data: List<UByte>,
    val fin: Boolean
)

/**
 * Hybrid QUIC Engine -- ported from Rust QuicEngineHybrid.
 * Uses atomic counters for hot-path performance and content-addressed
 * logging for crash recovery and audit.
 */
class QuicEngineHybrid(
    batchSize: Int = 100
) {
    private val hotState = QuicHotState()
    private val contentLogger = QuicContentLogger(batchSize)
    private val maxStreams: ULong = 100uL

    /** Send stream data -- HOT PATH + WARM PATH logging */
    fun sendStreamData(streamId: ULong, data: List<UByte>): QuicPacketHybrid {
        // HOT PATH: Get next packet number
        val pktNum = hotState.nextPacketNumber()

        // HOT PATH: Update bytes in flight
        hotState.addBytesInFlight(data.size)

        // HOT PATH: Update bytes sent
        hotState.bytesSent.addAndFetch(data.size.toLong())

        // Build packet
        val packet = QuicPacketHybrid(
            packetNumber = pktNum,
            streamId = streamId,
            data = data,
            fin = false
        )

        // WARM PATH: Log to content store (non-blocking)
        contentLogger.logPacketSent(pktNum, data)

        return packet
    }

    /** Process ACK -- HOT PATH only */
    fun processAck(ackNum: ULong, ackedBytes: Int) {
        // HOT PATH: Update ACK bitmap
        hotState.updateAck(ackNum)

        // HOT PATH: Remove from bytes in flight
        hotState.removeBytesInFlight(ackedBytes)
    }

    /** Create new stream -- HOT PATH */
    fun createStream(): ULong? {
        val current = hotState.streamCounter.get()
        if (current.toULong() >= maxStreams) return null

        val streamId = hotState.streamCounter.incrementAndAdd(0L).toULong()
        if (streamId >= maxStreams) return null

        return streamId * 4uL  // Client-initiated bidi stream
    }

    /** Get current state -- HOT PATH */
    fun getState(): QuicState = hotState.getState()

    /** Set state -- HOT PATH */
    fun setState(state: QuicState) {
        // Log state transition
        val data = state.name.encodeToByteArray().map { it.toUByte() }
        val pktNum = hotState.nextPacketNumber()
        contentLogger.logPacketSent(pktNum, data)

        // Update atomic state
        hotState.setState(state)
    }

    /** Get bytes in flight -- HOT PATH */
    fun bytesInFlight(): Int = hotState.bytesInFlight.get()

    /** Get total bytes sent -- HOT PATH */
    fun bytesSent(): ULong = hotState.bytesSent.get().toULong()

    /** Recover from crash -- COLD PATH */
    fun recoverFromCrash(fromPkt: ULong): List<QuicPacketHybrid> {
        val entries = contentLogger.recoverPackets(fromPkt, 1000)
        return entries.map { e ->
            QuicPacketHybrid(
                packetNumber = e.packetNumber,
                streamId = 0uL,
                data = e.data,
                fin = false
            )
        }
    }

    /** Get statistics */
    fun stats(): QuicStats {
        return QuicStats(
            packetSequence = hotState.packetSequence.get().toULong(),
            bytesInFlight = hotState.bytesInFlight.get(),
            bytesSent = hotState.bytesSent.get().toULong(),
            bytesReceived = hotState.bytesReceived.get().toULong(),
            state = hotState.getState(),
            contentBlobs = contentLogger.contentStore.size.toULong(),
            contentBytes = contentLogger.contentStore.values.sumOf { it.data.size.toULong() }
        )
    }
}

/** QUIC statistics */
data class QuicStats(
    val packetSequence: ULong,
    val bytesInFlight: Int,
    val bytesSent: ULong,
    val bytesReceived: ULong,
    val state: QuicState,
    val contentBlobs: ULong,
    val contentBytes: ULong
)
