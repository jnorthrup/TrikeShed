package dev.jnorthrup.ngsctp.ml

/**
 * ML Congestion Controller Slot
 * 
 * Placeholder for ONNX/TFLite model inference inside the congestion control loop.
 * This allows ML-based congestion control using trained models.
 * 
 * Supported models:
 * - TinyONNX models (recommended for embedded)
 * - TFLite Micro models
 * - BBR-style model-based predictions
 * - TCP CUBIC-style heuristic fallback
 * 
 * The model can be trained on:
 * - TCP CUBIC features
 * - BBR features  
 * - Custom SCTP multi-path features
 * - Network topology features
 */
object CongestionModelLoader {
    
    /**
     * Load a TinyONNX or TFLite model for inference
     * 
     * @param modelPath Path to the model file
     * @return CongestionPredictor instance
     */
    fun loadModel(modelPath: String): CongestionPredictor? {
        // Placeholder - actual implementation would use:
        // - ONNX Runtime for TinyONNX models
        // - TFLite for TFLite Micro models
        
        // For now, return a default predictor
        return DefaultPredictor()
    }
    
    /**
     * Create a predictor from embedded model bytes
     */
    fun loadModel(modelBytes: ByteArray): CongestionPredictor? {
        // Placeholder for embedded model loading
        return DefaultPredictor()
    }
    
    /**
     * Create a BBR-style predictor
     */
    fun createBBRPredictor(): CongestionPredictor = BBRCongestionPredictor()
    
    /**
     * Create a CUBIC-style predictor
     */
    fun createCubicPredictor(): CongestionPredictor = CubicCongestionPredictor()
}

/**
 * Congestion prediction interface
 */
interface CongestionPredictor {
    /**
     * Predict optimal congestion window
     * 
     * @param features Input features from current connection state
     * @return Recommended cwnd in bytes
     */
    fun predictCwnd(features: CongestionFeatures): UInt
    
    /**
     * Predict whether to perform slow start or congestion avoidance
     * 
     * @return true for slow start, false for congestion avoidance
     */
    fun predictPhase(features: CongestionFeatures): Boolean
    
    /**
     * Get recommended ssthresh value
     */
    fun predictSsthresh(features: CongestionFeatures): UInt
}

/**
 * Congestion control input features
 */
data class CongestionFeatures(
    val rtt: Long,                    // Round-trip time in microseconds
    val rttVariance: Long,            // RTT variance
    val bytesInFlight: UInt,           // Current bytes in flight
    val cwnd: UInt,                   // Current congestion window
    val ssthresh: UInt,              // Current slow start threshold
    val packetLossRate: Float,        // Recent loss rate (0.0 - 1.0)
    val ackRate: UInt,               // ACKs per second
    val pathCount: Int,              // Number of active paths
    val streamCount: Int,            // Number of active streams
    val priority: Int,               // Traffic priority
    val intent: String,              // Traffic intent (e.g., "allreduce-gradient")
    val timestamp: Long              // Current timestamp
)

/**
 * Default predictor - falls back to TCP CUBIC-like behavior
 */
class DefaultPredictor : CongestionPredictor {
    override fun predictCwnd(features: CongestionFeatures): UInt {
        // Simple CUBIC-like growth
        val growth = features.cwnd / 2u
        return features.cwnd + growth
    }
    
    override fun predictPhase(features: CongestionFeatures): Boolean {
        return features.cwnd < features.ssthresh
    }
    
    override fun predictSsthresh(features: CongestionFeatures): UInt {
        // On loss, reduce to 70% of cwnd
        return (features.cwnd * 7u / 10u).coerceAtLeast(4380u)
    }
}

/**
 * BBR (Bottleneck Bandwidth and RTT) style predictor
 * 
 * BBR builds a model of the network path's bandwidth and RTT
 * to determine the appropriate sending rate.
 */
class BBRCongestionPredictor : CongestionPredictor {
    // BBR state
    private var bw: Double = 0.0           // Bottleneck bandwidth
    private var rtProp: Long = Long.MAX_VALUE  // RTT prop (minimum RTT)
    private var pacingRate: Double = 0.0
    private var cycleIndex: Int = 0
    private val cycleLength = 8
    
    companion object {
        private const val BBR_HIGH_GAIN = 2.89  // 1.25 / 0.43 typical
        private const val BBR_MIN_CWND = 4
    }
    
    override fun predictCwnd(features: CongestionFeatures): UInt {
        // Update RTT minimum
        if (features.rtt < rtProp) {
            rtProp = features.rtt
        }
        
        // Calculate delivery rate (bytes per RTT)
        val bytesDelivered = features.bytesInFlight
        if (bytesDelivered > 0u && features.rtt > 0) {
            val deliveryRate = bytesDelivered.toDouble() / (features.rtt.toDouble() / 1_000_000.0)
            // Update max bandwidth
            if (deliveryRate > bw) {
                bw = deliveryRate
            }
        }
        
        // Calculate pacing rate
        pacingRate = bw * BBR_HIGH_GAIN
        
        // Calculate cwnd based on BBR model
        val minRTT = rtProp.coerceAtLeast(1)
        val windowedBw = (bw * minRTT / 1_000_000.0).toUInt()
        val cwnd = (windowedBw * BBR_HIGH_GAIN).toUInt()
        
        // Apply minimum cwnd
        return cwnd.coerceAtLeast((BBR_MIN_CWND * 1448).toUInt()) // ~2*MTU
    }
    
    override fun predictPhase(features: CongestionFeatures): Boolean {
        // BBR uses different phases: STARTUP, DRAIN, PROBE_BW, PROBE_RTT
        // For simplicity, return true during STARTUP-like behavior
        return bw == 0.0 || features.packetLossRate < 0.01f
    }
    
    override fun predictSsthresh(features: CongestionFeatures): UInt {
        // In BBR, ssthresh is not the primary control
        // Return current cwnd as a reference
        return features.cwnd
    }
}

/**
 * TCP CUBIC-style predictor
 * 
 * CUBIC uses a cubic function to achieve:
 * - Fast convergence
 * - TCP friendliness
 * - Scaling independence
 */
class CubicCongestionPredictor : CongestionPredictor {
    // CUBIC state
    private var wMax: UInt = 0u   // Window at last loss
    private var timeSinceLoss: Long = 0  // Time since last loss event
    private var lastLossTime: Long = 0
    
    companion object {
        // CUBIC constants
        private const val C = 0.4
        private const val BETA = 0.7
        private const val TCP_SCALE = 1.0
    }
    
    override fun predictCwnd(features: CongestionFeatures): UInt {
        val currentTime = System.currentTimeMillis()
        val elapsed = (currentTime - lastLossTime)
        
        // Update wMax if this is first loss event
        if (wMax == 0u) {
            wMax = features.cwnd
        }
        
        // Calculate CUBIC window
        val t = elapsed.toDouble() / 1000.0  // Convert to seconds
        val wCubic = C * (t * t * t) + wMax.toDouble()
        
        // TCP-friendly region (for small t)
        val wTcp = features.ssthresh + (3 * BETA / (2 - BETA)) * (t / features.rtt.toDouble())
        
        // Use whichever is smaller
        val targetCwnd = if (wTcp < wCubic) wTcp else wCubic
        
        // Apply loss multiplier
        val reducedCwnd = targetCwnd * (1 - BETA)
        
        return reducedCwnd.toUInt().coerceAtLeast(4380u)
    }
    
    override fun predictPhase(features: CongestionFeatures): Boolean {
        // In CUBIC, we're in slow start if cwnd < wMax * beta
        return features.cwnd < (wMax * 0.5u)
    }
    
    override fun predictSsthresh(features: CongestionFeatures): UInt {
        // On loss, reduce to (1-beta) * cwnd
        wMax = features.cwnd
        lastLossTime = System.currentTimeMillis()
        return (features.cwnd * (1 - BETA).toUInt()).coerceAtLeast(4380u)
    }
}

/**
 * Prediction result with confidence
 */
data class Prediction(
    val value: UInt,
    val confidence: Float,  // 0.0 - 1.0
    val modelVersion: String
)
