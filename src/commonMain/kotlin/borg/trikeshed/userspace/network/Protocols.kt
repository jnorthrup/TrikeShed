package borg.trikeshed.userspace.network

/**
 * Network protocol detection and classification ported from literbike.
 */

enum class Protocol {
    Http, Https, Http2, Http3, Quic, Ssh, Tls, WebSocket, Raw, Unknown
}

class ProtocolDetector {
    private var buffer = mutableListOf<Byte>()
    private var detected: Protocol? = null

    fun feed(data: ByteArray) {
        buffer.addAll(data.toList())
        if (detected == null) {
            detected = detectProtocol()
        }
    }

    fun protocol(): Protocol? = detected

    private fun detectProtocol(): Protocol? {
        if (buffer.size < 4) return null

        val prefix = buffer.take(4).toByteArray().decodeToString()
        return when {
            prefix.startsWith("GET ") || prefix.startsWith("POST") ||
            prefix.startsWith("PUT ") || prefix.startsWith("HEAD") ||
            prefix.startsWith("DELE") -> Protocol.Http
            buffer[0] == 0x16.toByte() && buffer[1] == 0x03.toByte() -> Protocol.Tls
            prefix.startsWith("SSH-") -> Protocol.Ssh
            (buffer[0].toInt() and 0xf0) == 0xc0 -> Protocol.Quic
            else -> null
        }
    }

    fun reset() {
        buffer.clear()
        detected = null
    }

    companion object {
        fun detectProtocol(data: ByteArray): Protocol {
            val detector = ProtocolDetector()
            detector.feed(data)
            return detector.protocol() ?: Protocol.Unknown
        }
    }
}
