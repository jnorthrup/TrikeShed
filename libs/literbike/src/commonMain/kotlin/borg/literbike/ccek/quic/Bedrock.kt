package borg.literbike.ccek.quic

// ============================================================================
// QUIC Bedrock -- ported from bedrock.rs
// Core foundation types and utilities for the QUIC stack
// ============================================================================

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Bedrock connection state tracker.
 * Tracks connection lifecycle with atomic counters for observability.
 */
class QuicBedrockConnection(
    val connectionId: ULong,
    val localAddr: String,
    val remoteAddr: String
) {
    val packetsSent = AtomicLong(0L)
    val packetsReceived = AtomicLong(0L)
    val bytesSent = AtomicLong(0L)
    val bytesReceived = AtomicLong(0L)
    val isActive = AtomicBoolean(true)

    val createdAt: Long = System.currentTimeMillis()
    var lastActivity: Long = System.currentTimeMillis()

    fun markSent(bytes: Int) {
        packetsSent.incrementAndGet()
        bytesSent.addAndGet(bytes.toLong())
        lastActivity = System.currentTimeMillis()
    }

    fun markReceived(bytes: Int) {
        packetsReceived.incrementAndGet()
        bytesReceived.addAndGet(bytes.toLong())
        lastActivity = System.currentTimeMillis()
    }

    fun isActive(timeout: Duration = 30_000.milliseconds): Boolean {
        if (!isActive.get()) return false
        return System.currentTimeMillis() - lastActivity < timeout.inWholeMilliseconds
    }

    fun close() {
        isActive.set(false)
    }

    override fun toString(): String {
        return "QuicBedrockConnection(id=$connectionId, sent=${packetsSent.get()}, recv=${packetsReceived.get()})"
    }
}

/**
 * Bedrock connection registry.
 * Thread-safe map of connection ID to connection state.
 */
class QuicBedrockRegistry {
    private val connections = ConcurrentHashMap<ULong, QuicBedrockConnection>()

    fun register(conn: QuicBedrockConnection) {
        connections[conn.connectionId] = conn
    }

    fun unregister(connectionId: ULong) {
        connections.remove(connectionId)
    }

    fun get(connectionId: ULong): QuicBedrockConnection? = connections[connectionId]

    fun getActiveCount(): Int = connections.values.count { it.isActive() }

    fun getAll(): List<QuicBedrockConnection> = connections.values.toList()

    fun evictIdle(timeout: Duration = 30_000.milliseconds) {
        connections.values.filter { !it.isActive(timeout) }.forEach {
            it.close()
            connections.remove(it.connectionId)
        }
    }
}

/**
 * Bedrock packet tracker with sliding window.
 * Tracks sent/received packets for ACK generation and loss detection.
 */
class QuicBedrockPacketTracker(
    private val windowSize: Int = 1024
) {
    private val sentPackets = mutableMapOf<ULong, Long>()  // packetNumber -> timestamp
    private val receivedPackets = mutableMapOf<ULong, Long>()
    private val ackedPackets = mutableSetOf<ULong>()

    fun recordSent(packetNumber: ULong) {
        sentPackets[packetNumber] = System.currentTimeMillis()
    }

    fun recordReceived(packetNumber: ULong) {
        receivedPackets[packetNumber] = System.currentTimeMillis()
    }

    fun recordAcked(packetNumber: ULong) {
        ackedPackets.add(packetNumber)
        sentPackets.remove(packetNumber)
    }

    /** Get unacked sent packets (potential losses) */
    fun getUnackedPackets(timeout: Duration = 200.milliseconds): List<ULong> {
        val now = System.currentTimeMillis()
        return sentPackets.filter { (_, ts) -> now - ts > timeout.inWholeMilliseconds }.keys.toList()
    }

    /** Get packets ready for ACK */
    fun getPacketsToAck(): List<ULong> = receivedPackets.keys.sorted()

    fun clearAcked() {
        receivedPackets.clear()
    }

    fun getStats(): PacketTrackerStats = PacketTrackerStats(
        sentCount = sentPackets.size,
        receivedCount = receivedPackets.size,
        ackedCount = ackedPackets.size,
        unackedCount = getUnackedPackets().size
    )
}

data class PacketTrackerStats(
    val sentCount: Int,
    val receivedCount: Int,
    val ackedCount: Int,
    val unackedCount: Int
)

/**
 * Bedrock RTT estimator using EWMA (Exponential Weighted Moving Average).
 * Ported from Rust RTT estimation in bedrock.rs.
 */
class QuicBedrockRttEstimator(
    private val alpha: Double = 0.125,  // EWMA smoothing factor
    private val beta: Double = 0.25     // Variance smoothing factor
) {
    private var rttEstimate: Double = 0.0
    private var rttVariance: Double = 0.0
    private var minRtt: Long = Long.MAX_VALUE

    fun updateSample(sampleMs: Long) {
        minRtt = minOf(minRtt, sampleMs)

        if (rttEstimate == 0.0) {
            rttEstimate = sampleMs.toDouble()
            rttVariance = sampleMs.toDouble() / 2.0
        } else {
            val diff = sampleMs - rttEstimate
            rttEstimate += alpha * diff
            rttVariance += beta * (diff.absoluteValue - rttVariance)
        }
    }

    fun getEstimate(): Long = rttEstimate.toLong()
    fun getVariance(): Long = rttVariance.toLong()
    fun getMinRtt(): Long = if (minRtt == Long.MAX_VALUE) 0 else minRtt

    /** Get RTO (Retransmission Timeout) */
    fun getRto(): Long {
        // RTO = estimated_RTT + 4 * variance + 1000ms (minimum)
        return (rttEstimate + 4 * rttVariance).toLong().coerceAtLeast(1000)
    }
}
