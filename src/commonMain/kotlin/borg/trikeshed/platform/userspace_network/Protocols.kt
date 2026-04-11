package borg.literbike.userspace_network

/**
 * Network protocol detection and classification
 */
object ProtocolsModule {

    /**
     * Supported network protocols
     */
    enum class Protocol {
        Http, Https, Http2, Http3, Quic, Ssh, Tls, WebSocket, Raw, Unknown
    }

    /**
     * Protocol detector for identifying network protocols from byte streams
     */
    class ProtocolDetector {
        private val buffer = ByteArray(256)
        private var bufferLen = 0
        private var detected: Protocol? = null

        companion object {
            fun create(): ProtocolDetector = ProtocolDetector()
        }

        /**
         * Feed bytes to the detector
         */
        fun feed(data: ByteArray) {
            val toCopy = minOf(data.size, buffer.size - bufferLen)
            if (toCopy > 0) {
                data.copyInto(buffer, bufferLen, 0, toCopy)
                bufferLen += toCopy
            }

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
            if (bufferLen < 4) return null

            return when {
                // HTTP methods
                buffer.startsWith("GET ") || buffer.startsWith("POST") ||
                buffer.startsWith("PUT ") || buffer.startsWith("HEAD") ||
                buffer.startsWith("DELE") -> Protocol.Http

                // TLS handshake
                bufferLen >= 3 && buffer[0] == 0x16.toByte() && buffer[1] == 0x03.toByte() -> Protocol.Tls

                // SSH banner
                buffer.startsWith("SSH-") -> Protocol.Ssh

                // QUIC
                bufferLen >= 1 && (buffer[0].toInt() and 0xF0) == 0xC0 -> Protocol.Quic

                else -> null
            }
        }

        private fun ByteArray.startsWith(prefix: String): Boolean {
            if (size < prefix.length) return false
            return prefix.toByteArray().indices.all { i -> this[i] == prefix.toByteArray()[i] }
        }

        /**
         * Reset the detector
         */
        fun reset() {
            buffer.fill(0)
            bufferLen = 0
            detected = null
        }
    }

    /**
     * Detect protocol from a byte slice
     */
    fun detectProtocol(data: ByteArray): Protocol {
        val detector = ProtocolDetector.create()
        detector.feed(data)
        return detector.protocol() ?: Protocol.Unknown
    }
}
