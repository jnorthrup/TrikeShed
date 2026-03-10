package borg.trikeshed.net

import borg.trikeshed.context.currentHandlerRegistry

// Protocol handlers
typealias ProtocolHandler = suspend (ByteArray) -> ByteArray

enum class ProtocolId {
    HTTP,
    QUIC,
    SSH,
    UNKNOWN,
}

val quicHandler: ProtocolHandler = { data ->
    // QUIC-specific processing
    "QUIC: ${data.size} bytes processed".encodeToByteArray()
}

val httpHandler: ProtocolHandler = { data ->
    // HTTP-specific processing
    "HTTP: ${data.size} bytes processed".encodeToByteArray()
}

val sshHandler: ProtocolHandler = { data ->
    // SSH-specific processing
    "SSH: ${data.size} bytes processed".encodeToByteArray()
}

// Placeholder protocol detector for the focused routing slice.
fun detectProtocol(data: ByteArray): ProtocolId {
    return when {
        // HTTP: check method prefixes at byte 0
        data.size >= 4 && data[0] == 'G'.code.toByte() && data[1] == 'E'.code.toByte() && data[2] == 'T'.code.toByte() &&
            data[3] == ' '.code.toByte() -> ProtocolId.HTTP

        data.size >= 5 && data[0] == 'P'.code.toByte() && data[1] == 'O'.code.toByte() && data[2] == 'S'.code.toByte() &&
            data[3] == 'T'.code.toByte() &&
            data[4] == ' '.code.toByte() -> ProtocolId.HTTP

        data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'U'.code.toByte() && data[2] == 'T'.code.toByte() &&
            data[3] == ' '.code.toByte() -> ProtocolId.HTTP

        data.size >= 7 && data[0] == 'D'.code.toByte() && data[1] == 'E'.code.toByte() && data[2] == 'L'.code.toByte() &&
            data[3] == 'E'.code.toByte() &&
            data[4] == 'T'.code.toByte() &&
            data[5] == 'E'.code.toByte() &&
            data[6] == ' '.code.toByte() -> ProtocolId.HTTP

        data.size >= 5 && data[0] == 'H'.code.toByte() && data[1] == 'E'.code.toByte() && data[2] == 'A'.code.toByte() &&
            data[3] == 'D'.code.toByte() &&
            data[4] == ' '.code.toByte() -> ProtocolId.HTTP

        data.size >= 8 && data[0] == 'O'.code.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'T'.code.toByte() &&
            data[3] == 'I'.code.toByte() &&
            data[4] == 'O'.code.toByte() &&
            data[5] == 'N'.code.toByte() &&
            data[6] == 'S'.code.toByte() &&
            data[7] == ' '.code.toByte() -> ProtocolId.HTTP

        data.size >= 6 && data[0] == 'P'.code.toByte() && data[1] == 'A'.code.toByte() && data[2] == 'T'.code.toByte() &&
            data[3] == 'C'.code.toByte() &&
            data[4] == 'H'.code.toByte() &&
            data[5] == ' '.code.toByte() -> ProtocolId.HTTP

        // QUIC: first byte is 0x00 (QUIC long header) - minimal detection
        data.size >= 1 && data[0] == 0x00.toByte() -> ProtocolId.QUIC

        // SSH: starts with "SSH-" at byte 0
        data.size >= 4 && data[0] == 'S'.code.toByte() && data[1] == 'S'.code.toByte() && data[2] == 'H'.code.toByte() &&
            data[3] == '-'.code.toByte() -> ProtocolId.SSH

        else -> ProtocolId.UNKNOWN
    }
}

// Protocol detection and routing
suspend fun routeProtocol(data: ByteArray): ByteArray {
    val protocol = detectProtocol(data) // QUIC, HTTP, SSH, etc.
    val registry = currentHandlerRegistry<ProtocolId, ProtocolHandler>()
    val handler =
        registry?.get(protocol)
            ?: throw IllegalStateException("No handler for $protocol")

    val result = handler(data)
    println("Routed via $protocol: ${result.size} bytes")
    return result
}
