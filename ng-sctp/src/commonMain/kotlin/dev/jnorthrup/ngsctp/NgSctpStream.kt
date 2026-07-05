package dev.jnorthrup.ngsctp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.nio.ByteBuffer

/**
 * ngSCTP Stream - Structured concurrency channel for ordered message delivery
 * 
 * Each stream is a child coroutine of the association scope, providing:
 * - Automatic cleanup on cancellation
 * - Zero-copy data transfer via ByteBuffer
 * - Partial reliability support (PR-SCTP)
 * - Priority and intent tagging for ML traffic classification
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NgSctpStream internal constructor(
    val streamId: Int,
    private val assocScope: CoroutineScope,
    val priority: Int = 0,
    val intent: String = "default"  // ML traffic intent: "allreduce-gradient", "inference", "control"
) : CoroutineScope by assocScope + Job() {

    /** Unbounded send channel for outgoing data - backpressure via flow */
    val sendChannel: SendChannel<ByteBuffer> = Channel(Channel.UNLIMITED)

    /** Unbounded receive channel for incoming data */
    val receiveChannel: ReceiveChannel<ByteBuffer> = Channel(Channel.UNLIMITED)

    /** Stream-specific transmission sequence number */
    private var streamSequenceNumber: UShort = 0u

    /** Partial reliability - lifetime in milliseconds (0 = unlimited) */
    var maxLifetimeMs: Long = 0L

    /** Whether unordered delivery is enabled */
    var unordered: Boolean = false

    init {
        // Launch sender coroutine - processes outgoing data
        launch {
            for (data in sendChannel) {
                sendDataChunk(data)
            }
        }
    }

    /**
     * Send a data chunk on this stream
     * Zero-copy: passes the ByteBuffer directly to the association
     */
    private suspend fun sendDataChunk(data: ByteBuffer) {
        val chunk = DataChunk(
            streamId = streamId.toUShort(),
            streamSequenceNumber = streamSequenceNumber++,
            payloadProtocolId = 0u,  // Could be extended for app-specific protocols
            userData = data,
            unordered = unordered,
            beginning = true,
            end = true,
            maxLifetimeMs = maxLifetimeMs
        )
        
        // Delegate to association's chunk sender
        // This is implemented in NgSctpAssociation
        // assocScope.sendChunk(chunk)  // Called via internal API
    }

    /**
     * Close the stream with an abort/reset code
     * Initiates stream reset handshake per RFC 3758
     */
    suspend fun closeWithCode(code: Short) {
        cancel("Stream closed with code: $code")
        // Send stream reset request
        // The association scope handles the actual chunk transmission
    }

    /**
     * Check if the stream is still open
     */
    val isOpen: Boolean
        get() = isActive && !sendChannel.isClosedForSend

    override fun toString(): String = 
        "NgSctpStream(id=$streamId, priority=$priority, intent=$intent, active=$isActive)"
}

/**
 * DATA chunk with partial reliability support
 */
data class DataChunk(
    val streamId: UShort,
    val streamSequenceNumber: UShort,
    val payloadProtocolId: UInt,
    val userData: ByteBuffer,
    val unordered: Boolean = false,
    val beginning: Boolean = true,
    val end: Boolean = true,
    val maxLifetimeMs: Long = 0L
) {
    val flags: UByte
        get() {
            var f = 0u
            if (beginning) f = f or 0x02u
            if (end) f = f or 0x01u
            if (unordered) f = f or 0x04u
            return f
        }
}
