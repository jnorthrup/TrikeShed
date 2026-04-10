package borg.literbike.ccek.quic

// ============================================================================
// QUIC WAM -- ported from quic_wam.rs and wam.rs
// WAM engine integration for QUIC protocol processing
// CoroutineContext.Element progressions with Params/Captures/Effects/Purity/CAS-RAS/CAD-CAR
// ============================================================================

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * QUIC Packet types for WAM processing
 */
enum class WamQuicPacketType {
    Initial,
    ZeroRtt,
    Handshake,
    Retry,
    OneRtt
}

/**
 * QUIC Connection State for WAM
 */
enum class WamQuicConnectionState {
    Idle,
    Initial,
    Handshake,
    Connected,
    Closing,
    Closed,
    Draining
}

/**
 * Stream type for QUIC multiplexing
 */
enum class WamStreamType {
    Bidirectional,
    Unidirectional
}

/**
 * QUIC Packet structure for WAM processing
 */
data class WamQuicPacket(
    val header: WamQuicHeader,
    val payload: List<UByte>,
    val packetNumber: ULong,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * QUIC Header for WAM
 */
data class WamQuicHeader(
    val flags: UByte,
    val connectionId: ULong,
    val packetType: WamQuicPacketType,
    val version: UInt
)

/**
 * QUIC Stream for WAM multiplexing
 */
data class WamQuicStream(
    val streamId: ULong,
    val streamType: WamStreamType,
    var state: StreamState = StreamState.Idle,
    var flowControlLimit: ULong = 0uL,
    val dataBuffer: MutableList<UByte> = mutableListOf()
)

/**
 * Connection Parameters (WAM Params)
 */
data class WamConnectionParams(
    val connectionId: ULong,
    val localAddr: String,
    val remoteAddr: String,
    val initialPacketNumber: ULong
)

/**
 * Connection Captures (WAM Captures from listeners)
 */
data class WamConnectionCaptures(
    val receivedPackets: MutableList<WamQuicPacket> = mutableListOf(),
    val timeoutEvents: MutableList<WamTimeoutEvent> = mutableListOf(),
    val appData: MutableList<WamApplicationData> = mutableListOf()
)

/**
 * Connection Effects (WAM Effects and mutations)
 */
data class WamConnectionEffects(
    val stateChanges: MutableList<WamStateChange> = mutableListOf(),
    val ioOperations: MutableList<WamIoOperation> = mutableListOf(),
    val timerOperations: MutableList<WamTimerOperation> = mutableListOf()
)

/**
 * Connection Purity (WAM Pure transformations)
 */
data class WamConnectionPurity(
    val packetTransforms: MutableList<WamPacketTransform> = mutableListOf(),
    val cryptoOperations: MutableList<WamCryptoOperation> = mutableListOf(),
    val validations: MutableList<WamValidationResult> = mutableListOf()
)

/**
 * Connection Atomics (CAS/RAS operations)
 */
class WamConnectionAtomics {
    val connectionState = AtomicReference(WamQuicConnectionState.Idle)
    val packetNumber = AtomicLong(0L)
    val ackNumber = AtomicLong(0L)
    val bytesSent = AtomicLong(0L)
    val bytesReceived = AtomicLong(0L)
    val rttEstimate = AtomicLong(0L)  // microseconds
}

/**
 * Cons cell structures for CAD/CAR operations
 */
sealed class WamConsCell<T> {
    data class Cons<T>(val head: T, val tail: WamConsCell<T>?) : WamConsCell<T>()
    data object Nil : WamConsCell<Nothing>()
}

/**
 * QUIC Packet Cons cell (CAD/CAR)
 */
data class WamQuicPacketCons(
    val packet: WamQuicPacket,             // CAD: head value
    val next: WamQuicPacketCons?           // CAR: tail
)

/**
 * Ack range cons cell
 */
data class WamAckCons(
    val ackRange: Pair<ULong, ULong>,      // CAD: head value
    val next: WamAckCons?                  // CAR: tail
)

/**
 * Stream cons cell
 */
data class WamStreamCons(
    val stream: WamQuicStream,             // CAD: head value
    val next: WamStreamCons?               // CAR: tail
)

/**
 * Timeout event for WAM
 */
data class WamTimeoutEvent(
    val connectionId: ULong,
    val timeoutMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Application data for WAM
 */
data class WamApplicationData(
    val connectionId: ULong,
    val streamId: ULong,
    val data: List<UByte>
)

/**
 * State change record
 */
data class WamStateChange(
    val connectionId: ULong,
    val fromState: WamQuicConnectionState,
    val toState: WamQuicConnectionState,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * I/O operation record
 */
data class WamIoOperation(
    val connectionId: ULong,
    val operation: String,
    val bytes: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Timer operation record
 */
data class WamTimerOperation(
    val connectionId: ULong,
    val operation: String,
    val timeoutMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Packet transform record
 */
data class WamPacketTransform(
    val input: WamQuicPacket,
    val output: WamQuicPacket
)

/**
 * Crypto operation record
 */
data class WamCryptoOperation(
    val operation: String,
    val level: String,
    val bytes: Int
)

/**
 * Validation result
 */
data class WamValidationResult(
    val check: String,
    val passed: Boolean,
    val detail: String = ""
)

/**
 * QUIC Protocol with CoroutineContext.Element Structure.
 * Ported from Rust QUICProtocol.
 *
 * WAM blocks structured with:
 * - Params: Input parameters for each operation
 * - Captures: Values captured from listeners/environment
 * - Effects: Side effects and state mutations
 * - Purity: Pure functional transformations
 * - CAS/RAS: Compare-And-Swap/Read-And-Set atomics
 * - CAD/CAR: Cons-cell Car/Cdr list operations
 */
class QuicWamProtocol(
    val contextElements: WamContextElements
) {
    private val connections = mutableMapOf<ULong, WamQuicConnection>()
    private val packetPipeline = WamPacketPipeline()
    private val congestionState = WamCongestionState()

    /** Add a new connection */
    fun addConnection(conn: WamQuicConnection) {
        connections[conn.params.connectionId] = conn
    }

    /** Get connection by ID */
    fun getConnection(connectionId: ULong): WamQuicConnection? = connections[connectionId]

    /** Process a packet through the pipeline */
    fun processPacket(packet: WamQuicPacket): Result<Unit> = runCatching {
        val conn = connections[packet.header.connectionId]
            ?: throw IllegalStateException("Connection ${packet.header.connectionId} not found")

        packetPipeline.enqueue(packet)
        conn.capturePacket(packet)
    }

    /** Get active connection count */
    fun getActiveConnectionCount(): Int = connections.size

    /** Get congestion state */
    fun getCongestionState(): WamCongestionState = congestionState
}

/**
 * QUIC Context Elements -- CoroutineContext.Element Pattern
 */
data class WamContextElements(
    val connectionContext: WamConnectionContextElement,
    val streamContext: WamStreamContextElement,
    val cryptoContext: WamCryptoContextElement,
    val flowContext: WamFlowControlContextElement,
    val congestionContext: WamCongestionContextElement
)

/**
 * Connection Context Element with Atomic Operations
 */
data class WamConnectionContextElement(
    val params: WamConnectionParams,
    val captures: WamConnectionCaptures = WamConnectionCaptures(),
    val effects: WamConnectionEffects = WamConnectionEffects(),
    val purity: WamConnectionPurity = WamConnectionPurity(),
    val atomicState: WamConnectionAtomics = WamConnectionAtomics(),
    val consState: WamConnectionCons = WamConnectionCons()
)

/**
 * Connection Cons state (CAD/CAR operations)
 */
data class WamConnectionCons(
    val packetQueueHead: WamQuicPacketCons? = null,
    val ackList: WamAckCons? = null,
    val streamList: WamStreamCons? = null
)

/**
 * Stream Context Element
 */
data class WamStreamContextElement(
    val streams: MutableMap<ULong, WamQuicStream> = mutableMapOf(),
    val nextStreamId: AtomicLong = AtomicLong(0L)
)

/**
 * Crypto Context Element
 */
data class WamCryptoContextElement(
    val handshakeComplete: AtomicLong = AtomicLong(0L),
    val cryptoErrors: AtomicLong = AtomicLong(0L)
)

/**
 * Flow Control Context Element
 */
data class WamFlowControlContextElement(
    val connectionWindow: AtomicLong = AtomicLong(1_048_576L),
    val streamWindows: MutableMap<ULong, AtomicLong> = mutableMapOf()
)

/**
 * Congestion Context Element
 */
data class WamCongestionContextElement(
    val congestionWindow: AtomicLong = AtomicLong(65536L),
    val bytesInFlight: AtomicLong = AtomicLong(0L),
    val ssthresh: AtomicLong = AtomicLong(65536L)
)

/**
 * QUIC Connection for WAM
 */
class WamQuicConnection(
    val params: WamConnectionParams
) {
    val atomicState = WamConnectionAtomics()
    val captures = WamConnectionCaptures()

    fun capturePacket(packet: WamQuicPacket) {
        captures.receivedPackets.add(packet)
        atomicState.bytesReceived.addAndGet(packet.payload.size.toLong())
    }
}

/**
 * Packet Pipeline for WAM processing
 */
class WamPacketPipeline {
    private val queue = mutableListOf<WamQuicPacket>()

    fun enqueue(packet: WamQuicPacket) {
        queue.add(packet)
    }

    fun dequeue(count: Int): List<WamQuicPacket> {
        val drained = queue.take(count)
        queue.subList(0, minOf(count, queue.size)).clear()
        return drained
    }

    fun size(): Int = queue.size
    fun isEmpty(): Boolean = queue.isEmpty()
}

/**
 * Congestion State for WAM
 */
class WamCongestionState {
    var congestionWindow: ULong = 65536uL
    var bytesInFlight: ULong = 0uL
    var ssthresh: ULong = ULong.MAX_VALUE

    fun onPacketSent(bytes: ULong) {
        bytesInFlight += bytes
    }

    fun onPacketAcked(bytes: ULong) {
        bytesInFlight = bytesInFlight.coerceAtLeast(bytes) - bytes
        // Slow start
        if (congestionWindow < ssthresh) {
            congestionWindow += bytes
        } else {
            // Congestion avoidance
            congestionWindow += (bytes * 65536uL) / congestionWindow
        }
    }

    fun onPacketLost(bytes: ULong) {
        ssthresh = (bytesInFlight / 2uL).coerceAtLeast(2uL * 65536uL)
        congestionWindow = 65536uL  // Reset to initial window
        bytesInFlight = bytesInFlight.coerceAtLeast(bytes) - bytes
    }

    fun canSend(bytes: ULong): Boolean = bytesInFlight + bytes <= congestionWindow
}
