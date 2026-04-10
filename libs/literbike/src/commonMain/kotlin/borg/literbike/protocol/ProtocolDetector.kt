package borg.literbike.protocol

import kotlin.io.IOException

/**
 * Result type for protocol handler operations.
 * Port of HandlerResult = io::Result<()>
 */
typealias HandlerResult = Result<Unit>

/**
 * Trait for handling detected protocols.
 * Port of ProtocolHandler trait from detector.rs.
 */
interface ProtocolHandler {
    /** Handle incoming data for this protocol */
    fun handle(data: ByteArray): HandlerResult

    /** Get the protocol this handler supports */
    val protocol: Protocol

    /** Check if handler is ready for more data */
    fun isReady(): Boolean = true
}

/**
 * Unified protocol detector that wraps protocol detection logic.
 * Port of UnifiedDetector from detector.rs.
 */
class UnifiedDetector {
    private var detectedProtocol: Protocol? = null
    private val handlers = mutableListOf<ProtocolHandler>()

    fun addHandler(handler: ProtocolHandler) {
        handlers.add(handler)
    }

    /** Feed data to the detector */
    fun feed(data: ByteArray): Result<Protocol?> {
        detectedProtocol = Protocol.detect(data)
        return Result.success(detectedProtocol)
    }

    /** Get detected protocol */
    fun protocol(): Protocol? = detectedProtocol

    /** Dispatch data to the appropriate handler */
    fun dispatch(data: ByteArray): HandlerResult {
        val proto = detectedProtocol
            ?: return Result.failure(IOException("no protocol detected"))

        for (handler in handlers) {
            if (handler.protocol == proto && handler.isReady()) {
                return handler.handle(data)
            }
        }

        return Result.failure(IOException("no handler for protocol: $proto"))
    }

    /** Reset the detector */
    fun reset() {
        detectedProtocol = null
    }
}

/**
 * Protocol detection context for stateful detection.
 * Port of ProtocolContext from detector.rs.
 */
class ProtocolContext(
    private val detector: UnifiedDetector = UnifiedDetector(),
    private val buffer: MutableList<Byte> = mutableListOf(),
    var maxBufferSize: Int = 65536
) {
    companion object {
        fun create(): ProtocolContext = ProtocolContext()
    }

    /** Feed data and detect protocol */
    fun feed(data: ByteArray): Result<Protocol?> {
        if (buffer.size + data.size > maxBufferSize) {
            return Result.failure(IOException("buffer overflow"))
        }
        buffer.addAll(data.toList())
        return detector.feed(buffer.toByteArray())
    }

    /** Get detected protocol */
    fun protocol(): Protocol? = detector.protocol()

    /** Get buffered data */
    fun buffer(): ByteArray = buffer.toByteArray()

    /** Clear buffer */
    fun clear() {
        buffer.clear()
        detector.reset()
    }

    /** Add handler to internal detector */
    fun addHandler(handler: ProtocolHandler) {
        detector.addHandler(handler)
    }
}

/**
 * Detect protocol from byte slice - top-level convenience.
 */
fun detectProtocol(data: ByteArray): Protocol = Protocol.detect(data)

/**
 * Extended protocol detection including SCTP heuristic.
 */
fun detectProtocolExtended(data: ByteArray): Protocol {
    val protocol = detectProtocol(data)
    if (protocol != Protocol.Unknown) return protocol

    // Check for SCTP: first 4 bytes as src_port + dst_port, 4-byte verification tag
    if (data.size >= 12) {
        val srcPort = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val dstPort = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val verificationTag = ((data[4].toInt() and 0xFF) shl 24) or
                ((data[5].toInt() and 0xFF) shl 16) or
                ((data[6].toInt() and 0xFF) shl 8) or
                (data[7].toInt() and 0xFF)

        if ((srcPort > 1024 || dstPort > 1024) && verificationTag != 0) {
            return Protocol.Raw // SCTP uses raw IP
        }
    }

    return Protocol.Unknown
}
