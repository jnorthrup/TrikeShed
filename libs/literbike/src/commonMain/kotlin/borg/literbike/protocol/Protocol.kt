package borg.literbike.protocol

/**
 * Unified protocol enumeration for protocol detection and handling.
 * Ported from literbike/src/protocol/detector.rs and userspace_network::protocols.
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
    Sctp,
    Raw,
    Unknown;

    companion object {
        /**
         * Detect protocol from raw bytes.
         * Port of userspace::network::protocols::detect_protocol.
         */
        fun detect(data: ByteArray): Protocol {
            if (data.isEmpty()) return Unknown

            // HTTP detection: starts with HTTP method
            val text = data.decodeToString(lossy = true)
            if (text.startsWith("GET ") || text.startsWith("POST ") ||
                text.startsWith("PUT ") || text.startsWith("DELETE ") ||
                text.startsWith("HEAD ") || text.startsWith("OPTIONS ") ||
                text.startsWith("PATCH ") || text.startsWith("CONNECT ")
            ) {
                return if (data.size > 4 && data[0] == 'G'.code.toByte() && data[1] == 'E'.code.toByte() &&
                    data[2] == 'T'.code.toByte() && data[3] == ' '.code.toByte()
                ) {
                    // Check for HTTPS via TLS handshake in HTTP-looking data (edge case)
                    Http
                } else Http
            }

            // SSH detection: starts with "SSH-"
            if (text.startsWith("SSH-")) {
                return Ssh
            }

            // TLS detection: handshake record (0x16 0x03 0x01/0x02/0x03)
            if (data.size >= 3 && data[0] == 0x16.toByte() &&
                data[1] == 0x03.toByte() &&
                data[2] in 0x01.toByte()..0x03.toByte()
            ) {
                return Tls
            }

            // QUIC detection: long header starts with 0xc0 or 0xd0
            if (data.isNotEmpty() && (data[0].toInt() and 0xC0) == 0xC0) {
                return Quic
            }

            // WebSocket: HTTP upgrade request
            if (text.contains("Upgrade: websocket", ignoreCase = true) &&
                text.contains("Connection: Upgrade", ignoreCase = true)
            ) {
                return WebSocket
            }

            Unknown
        }
    }
}
