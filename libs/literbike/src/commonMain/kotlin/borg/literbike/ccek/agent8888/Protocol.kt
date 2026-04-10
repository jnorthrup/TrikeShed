package borg.literbike.ccek.agent8888

import kotlin.experimental.and

/**
 * Agent8888 Protocol Detection
 *
 * Protocol detection and handler framework for multi-protocol servers.
 * Supports HTTP, SOCKS5, WebSocket, WebRTC, PAC, WPAD, Bonjour, UPnP.
 */

/**
 * Protocol detection result
 */
enum class Protocol {
    Http,
    Socks5,
    WebSocket,
    WebRTC,
    Pac,        // Proxy Auto-Config
    Wpad,       // Web Proxy Auto-Discovery
    Bonjour,    // mDNS/DNS-SD
    Upnp,       // UPnP discovery
    Unknown
}

/**
 * Protocol detection result with buffered data
 */
data class ProtocolDetection(
    val protocol: Protocol,
    val buffer: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtocolDetection) return false
        return protocol == other.protocol && buffer.contentEquals(other.buffer)
    }

    override fun hashCode(): Int {
        var result = protocol.hashCode()
        result = 31 * result + buffer.contentHashCode()
        return result
    }
}

/**
 * CCEK detection result
 */
data class CcekDetectionResult(
    val protocol: Protocol,
    val confidence: UByte,
    val buffer: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CcekDetectionResult) return false
        return protocol == other.protocol && confidence == other.confidence && buffer.contentEquals(other.buffer)
    }

    override fun hashCode(): Int {
        var result = protocol.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + buffer.contentHashCode()
        return result
    }
}

/**
 * Detects the protocol based on the first few bytes
 */
fun detectProtocol(buffer: ByteArray): ProtocolDetection {
    val n = buffer.size
    if (n == 0) return ProtocolDetection(Protocol.Unknown, ByteArray(0))

    // SOCKS5 starts with version byte 0x05
    if (n >= 2 && buffer[0] == 0x05.toByte()) {
        return ProtocolDetection(Protocol.Socks5, buffer)
    }

    // Check for text-based protocols
    val text = buffer.decodeToString()
    val textUpper = text.uppercase()

    // HTTP methods
    val httpMethods = listOf("GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "CONNECT ", "PATCH ")
    for (method in httpMethods) {
        if (text.startsWith(method)) {
            // Check for WebSocket upgrade
            if (textUpper.contains("UPGRADE: WEBSOCKET")) {
                return ProtocolDetection(Protocol.WebSocket, buffer)
            }

            // Check for PAC file request
            if (text.contains("/proxy.pac") || text.contains("/wpad.dat")) {
                return ProtocolDetection(
                    if (text.contains("/wpad.dat")) Protocol.Wpad else Protocol.Pac,
                    buffer
                )
            }

            return ProtocolDetection(Protocol.Http, buffer)
        }
    }

    // UPnP M-SEARCH (SSDP)
    if (text.startsWith("M-SEARCH ") || text.startsWith("NOTIFY ")) {
        return ProtocolDetection(Protocol.Upnp, buffer)
    }

    // WebRTC STUN binding request (starts with 0x00 0x01)
    if (n >= 20 && buffer[0] == 0x00.toByte() && buffer[1] == 0x01.toByte()) {
        // STUN magic cookie at bytes 4-7: 0x2112A442
        if (n >= 8 &&
            buffer[4] == 0x21.toByte() &&
            buffer[5] == 0x12.toByte() &&
            buffer[6] == 0xA4.toByte() &&
            buffer[7] == 0x42.toByte()
        ) {
            return ProtocolDetection(Protocol.WebRTC, buffer)
        }
    }

    // mDNS/Bonjour detection
    if (n >= 12) {
        val flags = (buffer[2].toInt() and 0xFF) shl 8 or (buffer[3].toInt() and 0xFF)
        val opcode = (flags shr 11) and 0x0F

        if (opcode == 0 && (flags and 0x8000) != 0) {
            return ProtocolDetection(Protocol.Bonjour, buffer)
        }
    }

    return ProtocolDetection(Protocol.Unknown, buffer)
}

/**
 * Prefixed stream - wraps a stream with pre-read data
 */
class PrefixedStream<T>(
    val inner: T,
    private val prefix: ByteArray,
    private var prefixOffset: Int = 0
) {
    companion object {
        fun <T> new(inner: T, prefix: ByteArray): PrefixedStream<T> =
            PrefixedStream(inner, prefix)
    }

    fun readPrefix(buf: ByteArray): Int {
        if (prefixOffset >= prefix.size) return 0
        val remaining = prefix.size - prefixOffset
        val toCopy = minOf(remaining, buf.size)
        prefix.copyInto(buf, 0, prefixOffset, prefixOffset + toCopy)
        prefixOffset += toCopy
        return toCopy
    }

    fun hasPrefixRemaining(): Boolean = prefixOffset < prefix.size
}

/**
 * Handler function type
 */
fun interface ProtocolHandler {
    suspend fun handle(stream: PrefixedStream<ByteArray>): Result<Unit>
}

/**
 * Protocol handlers collection
 */
data class ProtocolHandlers(
    val http: ProtocolHandler,
    val socks5: ProtocolHandler,
    val websocket: ProtocolHandler? = null,
    val webrtc: ProtocolHandler? = null,
    val pac: ProtocolHandler? = null,
    val wpad: ProtocolHandler? = null,
    val bonjour: ProtocolHandler? = null,
    val upnp: ProtocolHandler? = null
)

/**
 * Handle a connection with protocol detection
 */
suspend fun handleConnection(
    buffer: ByteArray,
    handlers: ProtocolHandlers
): Result<Unit> {
    val detection = detectProtocol(buffer)
    val prefixedStream = PrefixedStream.new(buffer, buffer)

    return when (detection.protocol) {
        Protocol.Http -> handlers.http.handle(prefixedStream)
        Protocol.Socks5 -> handlers.socks5.handle(prefixedStream)
        Protocol.WebSocket -> (handlers.websocket ?: handlers.http).handle(prefixedStream)
        Protocol.WebRTC -> handlers.webrtc?.handle(prefixedStream)
            ?: Result.failure(IllegalStateException("WebRTC not supported"))
        Protocol.Pac -> (handlers.pac ?: handlers.http).handle(prefixedStream)
        Protocol.Wpad -> (handlers.wpad ?: handlers.http).handle(prefixedStream)
        Protocol.Bonjour -> handlers.bonjour?.handle(prefixedStream)
            ?: Result.failure(IllegalStateException("Bonjour not supported"))
        Protocol.Upnp -> handlers.upnp?.handle(prefixedStream)
            ?: Result.failure(IllegalStateException("UPnP not supported"))
        Protocol.Unknown -> Result.failure(IllegalStateException("Unknown protocol"))
    }
}

/**
 * BitFlags utility for protocol detection flags
 */
@JvmInline
value class BitFlags(val value: ULong = 0uL) {
    operator fun contains(flag: BitFlags): Boolean = (value and flag.value) != 0uL
    infix fun or(other: BitFlags): BitFlags = BitFlags(value or other.value)
    infix fun and(other: BitFlags): BitFlags = BitFlags(value and other.value)

    companion object {
        val EMPTY = BitFlags(0uL)
        fun of(flag: ULong): BitFlags = BitFlags(flag)
    }
}
