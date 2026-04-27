package borg.trikeshed.couch.userspace.network

import borg.trikeshed.couch.htx.HtxBlockType

/**
 * Stateless protocol detector — peeks at the first bytes of a connection
 * to determine the protocol (HTTP, TLS, SSH, HTTP/2).
 *
 * Feed bytes incrementally via feed(); call protocol() to get the current
 * detection state. Returns null until enough bytes have been seen.
 *
 * Detection rules:
 * - "GET " / "HEAD " / "POST " / "PUT " / "DELETE " / "OPTIONS " / "PATCH " → HTTP
 * - 0x16 (TLS handshake) → TLS
 * - "SSH-" → SSH
 * - 0x00 0x00 0x?? "SPDY" → HTTP/2 (not implemented — returns null)
 * - empty → null (need more data)
 */
class ProtocolDetector {

    private val buf = StringBuilder()
    private var tlsDetected = false

    /**
     * Feed a chunk of bytes into the detector.
     */
    fun feed(bytes: ByteArray) {
        // Peek at first byte for TLS handshake (0x16 = 0b00010110)
        if (buf.isEmpty() && bytes.isNotEmpty() && bytes[0] == 0x16.toByte()) {
            tlsDetected = true
            return
        }
        buf.append(String(bytes))
    }

    /**
     * Current detected protocol, or null if not enough data yet.
     * Once non-null, stays non-null for the lifetime of this detector.
     */
    fun protocol(): Protocol? {
        if (tlsDetected) return Protocol.TLS
        val s = buf.toString()
        return when {
            s.startsWith("GET ") || s.startsWith("HEAD ") ||
            s.startsWith("POST ") || s.startsWith("PUT ") ||
            s.startsWith("DELETE ") || s.startsWith("OPTIONS ") ||
            s.startsWith("PATCH ") || s.startsWith("HTTP/") -> Protocol.HTTP

            s.length >= 3 && s.substring(0, 3) == "SSH" && s[3] == '-' -> Protocol.SSH

            s.isEmpty() -> null

            // TLS detection: first byte = 0x16 (TLS Handshake)
            // We receive bytes so check first byte
            else -> null
        }
    }

    /**
     * Reset the detector — clear buffered bytes.
     */
    fun reset() {
        buf.clear()
        tlsDetected = false
    }
}

/** Detected protocol identity. */
enum class Protocol {
    HTTP,
    TLS,
    SSH,
    HTTP2,
}
