package dev.jnorthrup.ngsctp.ml

import kotlin.test.*

/**
 * Tests for ML Congestion Model predictors
 */
class CongestionModelTest {
    
    private fun createTestFeatures(
        rtt: Long = 100_000L,
        cwnd: UInt = 4380u,
        ssthresh: UInt = 65535u,
        bytesInFlight: UInt = 0u
    ): CongestionFeatures {
        return CongestionFeatures(
            rtt = rtt,
            rttVariance = 1000L,
            bytesInFlight = bytesInFlight,
            cwnd = cwnd,
            ssthresh = ssthresh,
            packetLossRate = 0f,
            ackRate = 1000u,
            pathCount = 1,
            streamCount = 1,
            priority = 0,
            intent = "default",
            timestamp = System.currentTimeMillis()
        )
    }
    
    @Test
    fun `test DefaultPredictor slow start`() {
        val predictor = DefaultPredictor()
        val features = createTestFeatures(cwnd = 4000u, ssthresh = 10000u)
        
        val isSlowStart = predictor.predictPhase(features)
        assertTrue(isSlowStart, "Should be in slow start when cwnd < ssthresh")
    }
    
    @Test
    fun `test DefaultPredictor congestion avoidance`() {
        val predictor = DefaultPredictor()
        val features = createTestFeatures(cwnd = 15000u, ssthresh = 10000u)
        
        val isSlowStart = predictor.predictPhase(features)
        assertFalse(isSlowStart, "Should be in congestion avoidance when cwnd >= ssthresh")
    }
    
    @Test
    fun `test DefaultPredictor cwnd growth`() {
        val predictor = DefaultPredictor()
        val features = createTestFeatures(cwnd = 10000u)
        
        val newCwnd = predictor.predictCwnd(features)
        assertTrue(newCwnd > features.cwnd, "cwnd should grow")
        assertEquals(features.cwnd + features.cwnd / 2u, newCwnd)
    }
    
    @Test
    fun `test DefaultPredictor ssthresh on loss`() {
        val predictor = DefaultPredictor()
        val features = createTestFeatures(cwnd = 10000u)
        
        val newSsthresh = predictor.predictSsthresh(features)
        assertEquals(7000u, newSsthresh, "ssthresh should be 70% of cwnd")
    }
    
    @Test
    fun `test BBRCongestionPredictor calculates cwnd`() {
        val predictor = BBRCongestionPredictor()
        val features = createTestFeatures(
            rtt = 50_000L,  // 50ms RTT
            bytesInFlight = 10000u
        )
        
        val newCwnd = predictor.predictCwnd(features)
        assertTrue(newCwnd >= 5792u, "BBR cwnd should be at least minimum (~4*MTU)")
    }
    
    @Test
    fun `test BBRCongestionPredictor updates bandwidth`() {
        val predictor = BBRCongestionPredictor()
        
        // First call with moderate delivery
        val features1 = createTestFeatures(rtt = 100_000L, bytesInFlight = 5000u)
        predictor.predictCwnd(features1)
        
        // Second call with higher delivery
        val features2 = createTestFeatures(rtt = 100_000L, bytesInFlight = 15000u)
        val cwnd2 = predictor.predictCwnd(features2)
        
        // cwnd should reflect higher bandwidth estimate
        assertTrue(cwnd2 >= 5000u)
    }
    
    @Test
    fun `test CubicCongestionPredictor ssthresh calculation`() {
        val predictor = CubicCongestionPredictor()
        val features = createTestFeatures(cwnd = 10000u)
        
        val newSsthresh = predictor.predictSsthresh(features)
        assertEquals(7000u, newSsthresh, "CUBIC ssthresh should be 70% of cwnd")
    }
    
    @Test
    fun `test CubicCongestionPredictor reduces cwnd after loss`() {
        val predictor = CubicCongestionPredictor()
        
        // First establish a wMax
        val initialFeatures = createTestFeatures(cwnd = 10000u)
        predictor.predictSsthresh(initialFeatures)  // This sets wMax
        
        // Now predict - should be reduced
        val newCwnd = predictor.predictCwnd(initialFeatures)
        assertTrue(newCwnd < 10000u, "CUBIC should reduce cwnd after loss")
    }
    
    @Test
    fun `test CongestionModelLoader creates predictors`() {
        val defaultPredictor = CongestionModelLoader.loadModel(\"/fake/path.onnx\")
        assertTrue(defaultPredictor is DefaultPredictor)
        
        val bbrPredictor = CongestionModelLoader.createBBRPredictor()
        assertTrue(bbrPredictor is BBRCongestionPredictor)
        
        val cubicPredictor = CongestionModelLoader.createCubicPredictor()
        assertTrue(cubicPredictor is CubicCongestionPredictor)
    }
    
    @Test
    fun `test Prediction data class`() {
        val prediction = Prediction(
            value = 10000u,
            confidence = 0.85f,
            modelVersion = \"1.0.0\"
        )
        
        assertEquals(10000u, prediction.value)
        assertEquals(0.85f, prediction.confidence)
        assertEquals(\"1.0.0\", prediction.modelVersion)
    }
}
