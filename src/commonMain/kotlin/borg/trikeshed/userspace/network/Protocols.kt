package borg.trikeshed.userspace.network

import borg.trikeshed.lib.ByteSeries

/**
 * Network protocol detection and classification ported from literbike.
 */

enum class Protocol {
    Http, Https, Http2, Http3, Quic, Ssh, Tls, WebSocket, Raw, Unknown
}

class ProtocolDetector {
    private var buffer = ByteArray(0)
    private var detected: Protocol? = null

    fun feed(data: ByteSeries) {
        append(data)
        if (detected == null) {
            detected = detectProtocol()
        }
    }

    fun feed(data: ByteArray) {
        feed(ByteSeries(data))
    }

    fun protocol(): Protocol? = detected

    private fun detectProtocol(): Protocol? {
        if (buffer.size < 4) return null

        val prefix = buffer.copyOfRange(0, 4).decodeToString()
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
        buffer = ByteArray(0)
        detected = null
    }

    private fun append(data: ByteSeries) {
        val bytes = data.clone().toArray()
        val merged = ByteArray(buffer.size + bytes.size)
        buffer.copyInto(merged, endIndex = buffer.size)
        bytes.copyInto(merged, destinationOffset = buffer.size)
        buffer = merged
    }

    companion object {
        fun detectProtocol(data: ByteSeries): Protocol {
            val detector = ProtocolDetector()
            detector.feed(data)
            return detector.protocol() ?: Protocol.Unknown
        }

        fun detectProtocol(data: ByteArray): Protocol = detectProtocol(ByteSeries(data))
    }
}
