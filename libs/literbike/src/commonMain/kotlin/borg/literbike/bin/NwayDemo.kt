package borg.literbike.bin

/**
 * N-Way Demo - Multi-provider protocol detection demonstration.
 * Ported from literbike/src/bin/nway_demo.rs.
 */

import borg.literbike.betanet.Anchor
import borg.literbike.betanet.ProtocolDetector
import borg.literbike.betanet.detectWithPolicy
import borg.literbike.rbcursive.RBCursive
import borg.literbike.rbcursive.Protocol
import borg.literbike.rbcursive.Signal

/**
 * N-way protocol detection result.
 */
data class NwayResult(
    val anchorMatch: Anchor?,
    val rbcursiveSignal: Signal,
    val detectedProtocol: Protocol,
    val detectionTimeNanos: Long
)

/**
 * Demo of N-way protocol detection.
 */
class NwayDemo {
    private val rbcursive = RBCursive.new()

    /**
     * Run detection across multiple anchors and data samples.
     */
    fun detectNway(anchors: List<Anchor>, data: ByteArray): NwayResult {
        val startTime = System.nanoTime()

        val anchorMatch = detectWithPolicy(anchors, data)
        val netTuple = borg.literbike.rbcursive.NetTuple(
            remoteAddr = byteArrayOf(127, 0, 0, 1),
            remotePort = 8080
        )
        val rbcursiveSignal = rbcursive.recognize(netTuple, data)

        val detectedProtocol = when (rbcursiveSignal) {
            is Signal.Accept -> rbcursiveSignal.protocol
            else -> Protocol.Unknown
        }

        val elapsed = System.nanoTime() - startTime

        return NwayResult(
            anchorMatch = anchorMatch,
            rbcursiveSignal = rbcursiveSignal,
            detectedProtocol = detectedProtocol,
            detectionTimeNanos = elapsed
        )
    }

    /**
     * Run demo with sample HTTP data.
     */
    fun runHttpDemo() {
        val httpData = "GET /api/v1/users HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()

        val anchors = listOf(
            Anchor(pattern = 0x4745542000000000L, priority = 10, mask = 0), // "GET "
            Anchor(pattern = 0x504F535400000000L, priority = 10, mask = 0), // "POST "
            Anchor(pattern = 0x0500000000000000L, priority = 5, mask = 0)   // SOCKS5
        )

        val result = detectNway(anchors, httpData)

        println("N-Way Detection Demo - HTTP")
        println("  Anchor match: ${result.anchorMatch != null}")
        println("  RBCursive signal: ${result.rbcursiveSignal}")
        println("  Detected protocol: ${result.detectedProtocol}")
        println("  Detection time: ${result.detectionTimeNanos / 1000} μs")
    }

    /**
     * Run demo with SOCKS5 data.
     */
    fun runSocks5Demo() {
        val socks5Data = byteArrayOf(0x05, 0x01, 0x00)

        val anchors = listOf(
            Anchor(pattern = 0x4745542000000000L, priority = 10, mask = 0),
            Anchor(pattern = 0x0500000000000000L, priority = 5, mask = 0)
        )

        val result = detectNway(anchors, socks5Data)

        println("N-Way Detection Demo - SOCKS5")
        println("  Anchor match: ${result.anchorMatch != null}")
        println("  RBCursive signal: ${result.rbcursiveSignal}")
        println("  Detected protocol: ${result.detectedProtocol}")
        println("  Detection time: ${result.detectionTimeNanos / 1000} μs")
    }

    /**
     * Run all demos.
     */
    fun runAllDemos() {
        println("=== N-Way Protocol Detection Demo ===\n")
        runHttpDemo()
        println()
        runSocks5Demo()
        println("\n=== Demo Complete ===")
    }
}

/**
 * Main entry point for N-Way Demo.
 */
fun runNwayDemo() {
    NwayDemo().runAllDemos()
}
