package dev.jnorthrup.ngsctp

import kotlinx.coroutines.*
import java.util.concurrent.atomic.*

/**
 * SCTP Congestion Control Implementation
 * 
 * Implements RFC 4960 Section 7:
 * - Slow Start
 * - Congestion Avoidance  
 * - Fast Recovery
 * 
 * Uses Atomic* for thread-safe updates from multiple coroutines.
 * Optionally integrates with ML-based congestion prediction.
 */
class CongestionControl(
    /** Initial congestion window (in bytes) - typically 2*MTU */
    private val initialCwnd: Int = 4380,
    /** Maximum congestion window (in bytes) */
    private val maxCwnd: Int = 1024 * 1024,
    /** Initial slow start threshold (in bytes) - infinite initially */
    private var ssthresh: Int = Int.MAX_VALUE,
    /** Current congestion window (in bytes) */
    private val _cwnd: AtomicInteger = AtomicInteger(initialCwnd),
    /** Last acknowledged TSN */
    private val _lastAckedTSN: AtomicInteger = AtomicInteger(0),
    /** Partial bytes acked in congestion avoidance */
    private val _partialBytesAcked: AtomicInteger = AtomicInteger(0),
    /** Current phase: SLOW_START, CONGESTION_AVOIDANCE, FAST_RECOVERY */
    private val _phase: AtomicReference<CongestionPhase> = AtomicReference(CongestionPhase.SLOW_START),
    /** ML predictor for hybrid mode (optional) */
    private var mlPredictor: dev.jnorthrup.ngsctp.ml.CongestionPredictor? = null,
    /** RTT tracking */
    private val _rtt: AtomicLong = AtomicLong(0),
    private val _rttVariance: AtomicLong = AtomicLong(0),
    private val _rttSampleCount: AtomicInteger = AtomicInteger(0),
    private var _lastRttSampleTime: Long = 0,
    /** Loss tracking */
    private val _recentLossCount: AtomicInteger = AtomicInteger(0),
    private val _totalAckCount: AtomicInteger = AtomicInteger(0)
) {
    companion object {
        /** Typical MTU - 576, 1280, 1492, etc. */
        const val DEFAULT_MTU = 1492
        /** Minimum MTU */
        const val MIN_MTU = 576
    }
    
    enum class CongestionPhase {
        SLOW_START,      // cwnd < ssthresh
        CONGESTION_AVOIDANCE,  // cwnd >= ssthresh
        FAST_RECOVERY    // After fast retransmit
    }
    
    /** Current congestion window in bytes */
    val cwnd: Int get() = _cwnd.get()
    
    /** Current slow start threshold */
    val currentSsthresh: Int get() = ssthresh
    
    /** Current congestion phase */
    val phase: CongestionPhase get() = _phase.get()
    
    /** Last acknowledged TSN */
    var lastAckedTSN: Int
        get() = _lastAckedTSN.get()
        set(value) { _lastAckedTSN.set(value) }
    
    /** Current RTT in microseconds */
    val rtt: Long get() = _rtt.get()
    
    /** RTT variance */
    val rttVariance: Long get() = _rttVariance.get()
    
    /** Recent packet loss rate (0.0 - 1.0) */
    val lossRate: Float
        get() {
            val total = _totalAckCount.get()
            return if (total > 0) _recentLossCount.get().toFloat() / total else 0f
        }
    
    /** Set ML predictor for hybrid mode */
    fun setMLPredictor(predictor: dev.jnorthrup.ngsctp.ml.CongestionPredictor) {
        mlPredictor = predictor
    }
    
    /** Enable/disable ML predictions */
    private var _useML: Boolean = false
    
    fun setUseML(enabled: Boolean) {
        // Cannot enable ML without a predictor
        if (enabled && mlPredictor == null) {
            _useML = false
        } else {
            _useML = enabled
        }
    }
    
    fun isUseMLEnabled(): Boolean = _useML
    
    /**
     * Update RTT measurement (called when ACK arrives for sent data)
     * @param rttMicros RTT in microseconds
     */
    fun updateRTT(rttMicros: Long) {
        val oldRtt = _rtt.get()
        val oldVariance = _rttVariance.get()
        val count = _rttSampleCount.incrementAndGet()
        
        // Exponential moving average
        val alpha = if (count < 8) 1.0 / count else 0.125
        val newRtt = (oldRtt * (1 - alpha) + rttMicros * alpha).toLong()
        _rtt.set(newRtt)
        
        // Update variance
        val rttDiff = kotlin.math.abs(rttMicros - oldRtt)
        val newVariance = (oldVariance * (1 - alpha) + rttDiff * alpha).toLong()
        _rttVariance.set(newVariance)
        
        _lastRttSampleTime = System.currentTimeMillis()
    }
    
    /**
     * Record a packet loss event
     */
    fun recordLoss() {
        _recentLossCount.incrementAndGet()
    }
    
    /**
     * Build current features for ML prediction
     */
    fun buildFeatures(
        bytesInFlight: Int,
        pathCount: Int = 1,
        streamCount: Int = 1,
        priority: Int = 0,
        intent: String = "default"
    ): dev.jnorthrup.ngsctp.ml.CongestionFeatures {
        return dev.jnorthrup.ngsctp.ml.CongestionFeatures(
            rtt = rtt,
            rttVariance = rttVariance,
            bytesInFlight = bytesInFlight.toUInt(),
            cwnd = cwnd.toUInt(),
            ssthresh = ssthresh.toUInt(),
            packetLossRate = lossRate,
            ackRate = 0u, // Would need more sophisticated tracking
            pathCount = pathCount,
            streamCount = streamCount,
            priority = priority,
            intent = intent,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Called when a new data chunk is about to be sent
     * Returns the number of bytes allowed to send
     */
    fun bytesAllowedToSend(outstandingBytes: Int): Int {
        // If ML is enabled and we have a predictor, use it
        if (_useML && mlPredictor != null) {
            val features = buildFeatures(outstandingBytes)
            val mlCwnd = mlPredictor!!.predictCwnd(features).toInt()
            val available = mlCwnd - outstandingBytes
            return if (available > 0) available else 0
        }
        
        val available = cwnd - outstandingBytes
        return if (available > 0) available else 0
    }
    
    /**
     * Called when a SACK is received with cumulative ACK
     * 
     * @param cumulativeAckTSN The TSN acknowledged
     * @param previousAckTSN The previous cumulative ACK
     * @param gapAckBlocks Acknowledged ranges (start, end) from SACK
     * @param dataBytesInFlight Total bytes currently unacknowledged
     */
    fun onSackReceived(
        cumulativeAckTSN: UInt,
        previousAckTSN: UInt,
        gapAckBlocks: List<Pair<UInt, UInt>>,
        dataBytesInFlight: Int
    ) {
        val newAck = cumulativeAckTSN.toInt()
        val prevAck = previousAckTSN.toInt()
        val ackAdvance = newAck - prevAck
        
        if (ackAdvance <= 0) return // No new data acknowledged
        
        // Update last acked
        lastAckedTSN = newAck
        
        when (_phase.get()) {
            CongestionPhase.SLOW_START -> {
                // RFC 4960: During slow start, cwnd is increased by one SCTP packet
                // per incoming SACK, up to ssthresh
                if (cwnd < ssthresh) {
                    // Increase cwnd by min(ackAdvance, MTU)
                    val increase = minOf(ackAdvance, DEFAULT_MTU)
                    val newCwnd = minOf(cwnd + increase, ssthresh)
                    _cwnd.set(newCwnd)
                    
                    if (cwnd >= ssthresh) {
                        _phase.set(CongestionPhase.CONGESTION_AVOIDANCE)
                    }
                } else {
                    _phase.set(CongestionPhase.CONGESTION_AVOIDANCE)
                    onSackReceived(cumulativeAckTSN, previousAckTSN, gapAckBlocks, dataBytesInFlight)
                }
            }
            
            CongestionPhase.CONGESTION_AVOIDANCE -> {
                // RFC 4960: During congestion avoidance, cwnd is increased by 
                // approximately one MTU per RTT (bytes_acked / cwnd * MTU)
                val currentCwnd = cwnd
                val partialBytes = _partialBytesAcked.addAndGet(ackAdvance)
                
                // When partial bytes acked exceeds cwnd, increase cwnd by MTU
                if (partialBytes >= currentCwnd) {
                    _partialBytesAcked.addAndGet(-currentCwnd)
                    val newCwnd = minOf(currentCwnd + DEFAULT_MTU, maxCwnd)
                    _cwnd.set(newCwnd)
                }
            }
            
            CongestionPhase.FAST_RECOVERY -> {
                // RFC 4960: When a SACK acknowledges all outstanding data
                // up to the recovery point, exit fast recovery
                val recoveryPoint = lastAckedTSN // Would track this separately
                if (cumulativeAckTSN >= recoveryPoint.toUInt()) {
                    _phase.set(CongestionPhase.CONGESTION_AVOIDANCE)
                    ssthresh = cwnd / 2
                    _cwnd.set(ssthresh + DEFAULT_MTU)
                } else {
                    // Still in fast recovery - per-gap cwnd update
                    // For each gap, cwnd remains the same
                }
            }
        }
    }
    
    /**
     * Called when a timeout occurs (no SACK received for a while)
     */
    fun onTimeout() {
        val currentCwnd = cwnd
        
        // Save current cwnd as ssthresh
        ssthresh = maxOf(currentCwnd / 2, 2 * DEFAULT_MTU)
        
        // Set cwnd to one packet (RFC 4960)
        _cwnd.set(DEFAULT_MTU)
        
        // Enter slow start
        _phase.set(CongestionPhase.SLOW_START)
        
        // Reset partial bytes acked
        _partialBytesAcked.set(0)
    }
    
    /**
     * Called when 3+ duplicate SACKs received (fast retransmit trigger)
     */
    fun onDuplicateSack() {
        // RFC 4960: Enter fast recovery
        val currentCwnd = cwnd
        
        // ssthresh = cwnd / 2
        ssthresh = maxOf(currentCwnd / 2, 2 * DEFAULT_MTU)
        
        // cwnd = ssthresh + 3 * MTU
        _cwnd.set(ssthresh + 3 * DEFAULT_MTU)
        
        _phase.set(CongestionPhase.FAST_RECOVERY)
    }
    
    /**
     * Called when CWR chunk received (ECN feedback from peer)
     * RFC 4960 Section 12.4
     */
    fun onCwrReceived(lowestTSN: UInt) {
        // The peer has indicated it reduced its cwnd in response to ECN
        // This is informational - we've already reduced our cwnd when we sent ECNE
        // Log the ECN reduction for monitoring
        println("ECN: Peer acknowledged congestion, reduced cwnd to $cwnd")
    }
    
    /**
     * Get the number of packets allowed in flight
     */
    fun packetsInFlightAllowed(): Int {
        return cwnd / DEFAULT_MTU
    }
    
    override fun toString(): String {
        return "CongestionControl(cwnd=$cwnd, ssthresh=$ssthresh, phase=$phase, useML=$_useML)"
    }
}

/**
 * Send buffer management for tracking outstanding DATA chunks
 */
class SendBuffer(
    /** Maximum buffer size in bytes */
    private val bufferSize: Int = 64 * 1024
) {
    /** Outstanding chunks tracked by TSN */
    private val outstanding = ConcurrentHashMap<Int, PendingChunk>()
    
    /** Total bytes in flight */
    private val _bytesInFlight = AtomicInteger(0)
    
    /** Next TSN to assign */
    private val _nextTSN = AtomicInteger(0)
    
    /** Timer job for RTO */
    private var retransmitTimer: Job? = null
    
    val bytesInFlight: Int get() = _bytesInFlight.get()
    val nextTSN: UInt get() = _nextTSN.get().toUInt()
    val outstandingCount: Int get() = outstanding.size
    
    data class PendingChunk(
        val tsn: UInt,
        val streamId: UShort,
        val streamSeq: UShort,
        val data: ByteArray,
        val sentAt: Long = System.currentTimeMillis(),
        var acked: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PendingChunk
            return tsn == other.tsn
        }
        
        override fun hashCode(): Int = tsn.hashCode()
    }
    
    /**
     * Add a new chunk to the send buffer
     */
    fun addChunk(data: ByteArray, streamId: UShort, streamSeq: UShort): UInt {
        val tsn = _nextTSN.getAndIncrement().toUInt()
        outstanding[tsn.toInt()] = PendingChunk(tsn, streamId, streamSeq, data)
        _bytesInFlight.addAndGet(data.size)
        return tsn
    }
    
    /**
     * Mark chunks as acknowledged (cumulative and gap acks)
     */
    fun ackChunks(cumulativeAck: UInt, gapAcks: List<Pair<UInt, UInt>>): List<PendingChunk> {
        val acked = mutableListOf<PendingChunk>()
        val cumAckInt = cumulativeAck.toInt()
        
        // First, check if cumulative ACK advances
        val toRemove = mutableListOf<Int>()
        
        for ((tsn, chunk) in outstanding) {
            if (tsn <= cumAckInt) {
                // This chunk is acked by cumulative ACK
                if (!chunk.acked) {
                    chunk.acked = true
                    acked.add(chunk)
                    _bytesInFlight.addAndGet(-chunk.data.size)
                }
                toRemove.add(tsn)
            }
        }
        
        // Remove acked chunks
        for (tsn in toRemove) {
            outstanding.remove(tsn)
        }
        
        // Then process gap ack blocks
        for ((start, end) in gapAcks) {
            val startInt = start.toInt()
            val endInt = end.toInt()
            
            for (tsn in startInt..endInt) {
                val chunk = outstanding[tsn]
                if (chunk != null && !chunk.acked) {
                    chunk.acked = true
                    acked.add(chunk)
                    _bytesInFlight.addAndGet(-chunk.data.size)
                    outstanding.remove(tsn)
                }
            }
        }
        
        return acked
    }
    
    /**
     * Get all unacknowledged chunks for potential retransmission
     */
    fun getUnackedChunks(): Collection<PendingChunk> {
        return outstanding.values.filter { !it.acked }
    }
    
    /**
     * Get oldest unacknowledged chunk for RTO timer
     */
    fun getOldestUnacked(): PendingChunk? {
        return outstanding.values.minByOrNull { it.sentAt }
    }
    
    /**
     * Check if buffer is full
     */
    fun isFull(): Boolean {
        return _bytesInFlight.get() >= bufferSize
    }
    
    /**
     * Clear all chunks (on association close)
     */
    fun clear() {
        outstanding.clear()
        _bytesInFlight.set(0)
    }
}

/**
 * SCTP Heartbeat mechanism for connection monitoring
 */
class HeartbeatManager(
    private val scope: CoroutineScope,
    /** Heartbeat interval in milliseconds */
    var heartbeatInterval: Long = 30000L,
    /** Maximum number of consecutive failed heartbeats before declaring peer dead */
    var maxRetries: Int = 3
) {
    private val _failedHeartbeats = AtomicInteger(0)
    private val _lastHeartbeatResponse = AtomicLong(System.currentTimeMillis())
    private var heartbeatJob: Job? = null
    private var isRunning = AtomicBoolean(false)
    
    /** Channel for outgoing heartbeat requests */
    val heartbeatRequests = Channel<ByteArray>(Channel.BUFFERED)
    
    val failedHeartbeatCount: Int get() = _failedHeartbeats.get()
    val isPeerAlive: Boolean get() = _failedHeartbeats.get() < maxRetries
    
    /**
     * Start the heartbeat timer
     */
    fun start() {
        if (isRunning.getAndSet(true)) return
        
        heartbeatJob = scope.launch {
            while (isActive && isRunning.get()) {
                delay(heartbeatInterval)
                sendHeartbeat()
            }
        }
    }
    
    /**
     * Stop the heartbeat timer
     */
    fun stop() {
        isRunning.set(false)
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * Send a heartbeat (called by transmit loop)
     */
    private suspend fun sendHeartbeat() {
        // Create heartbeat info with local timestamp and address
        val heartbeatInfo = ByteArray(12)
        val timestamp = System.currentTimeMillis()
        heartbeatInfo[0] = ((timestamp shr 24) and 0xFF).toByte()
        heartbeatInfo[1] = ((timestamp shr 16) and 0xFF).toByte()
        heartbeatInfo[2] = ((timestamp shr 8) and 0xFF).toByte()
        heartbeatInfo[3] = (timestamp and 0xFF).toByte()
        
        heartbeatRequests.send(heartbeatInfo)
    }
    
    /**
     * Called when heartbeat acknowledgment is received
     */
    fun onHeartbeatAck() {
        _failedHeartbeats.set(0)
        _lastHeartbeatResponse.set(System.currentTimeMillis())
    }
    
    /**
     * Called when heartbeat times out (no response)
     */
    fun onHeartbeatTimeout() {
        val failures = _failedHeartbeats.incrementAndGet()
        if (failures >= maxRetries) {
            // Peer is considered dead
            scope.cancel("Heartbeat timeout - peer unreachable")
        }
    }
    
    /**
     * Get time since last successful heartbeat
     */
    fun timeSinceLastHeartbeat(): Long {
        return System.currentTimeMillis() - _lastHeartbeatResponse.get()
    }
}
