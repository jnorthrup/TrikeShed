package borg.trikeshed.platform.network

/**
 * Network protocol detection and classification
 */

/**
 * Supported network protocols
 */
enum class Protocol {
    Http,
    Https,
    Http2,
    Http3,
    Quic,
    Ssh,
    Tls,
    WebSocket,
    Raw,
    Unknown
}

/**
 * Protocol detector for identifying network protocols from byte streams
 */
class ProtocolDetector {
    private val buffer = mutableListOf<Byte>()
    private var detected: Protocol? = null

    companion object {
        fun create(): ProtocolDetector = ProtocolDetector()
    }

    /**
     * Feed bytes to the detector
     */
    fun feed(data: ByteArray) {
        buffer.addAll(data.toList())
        if (detected == null) {
            detected = detectProtocol()
        }
    }

    /**
     * Get the detected protocol if any
     */
    fun protocol(): Protocol? = detected

    /**
     * Detect protocol from buffered data
     */
    private fun detectProtocol(): Protocol? {
        if (buffer.size < 4) return null

        return when {
            buffer.startsWith("GET ") || buffer.startsWith("POST") ||
            buffer.startsWith("PUT ") || buffer.startsWith("HEAD") ||
            buffer.startsWith("DELE") -> Protocol.Http

            buffer.size >= 3 && buffer[0] == 0x16.toByte() && buffer[1] == 0x03.toByte() -> Protocol.Tls

            buffer.startsWith("SSH-") -> Protocol.Ssh

            buffer.isNotEmpty() && (buffer[0].toInt() and 0xF0) == 0xC0 -> Protocol.Quic

            else -> null
        }
    }

    /**
     * Reset the detector
     */
    fun reset() {
        buffer.clear()
        detected = null
    }

    private fun List<Byte>.startsWith(prefix: String): Boolean {
        if (size < prefix.length) return false
        return prefix.toByteArray().toList().subList(0, size.coerceAtMost(prefix.length)) ==
            this.subList(0, prefix.length.coerceAtMost(size))
    }
}

/**
 * Detect protocol from a byte slice
 */
fun detectProtocol(data: ByteArray): Protocol {
    val detector = ProtocolDetector()
    detector.feed(data)
    return detector.protocol() ?: Protocol.Unknown
}
