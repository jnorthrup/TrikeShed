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
    val str = data.decodeToString()
    val upper = str.uppercase()
    return when {
        upper.startsWith("GET ") || upper.startsWith("POST ") || upper.startsWith("PUT ") ||
            upper.startsWith("DELETE ") || upper.startsWith("HEAD ") || upper.startsWith("OPTIONS ") ||
            upper.startsWith("PATCH ") || upper.contains("HTTP/") -> ProtocolId.HTTP
        upper.contains("QUIC") -> ProtocolId.QUIC
        upper.contains("SSH") -> ProtocolId.SSH
        else -> ProtocolId.UNKNOWN
    }
}

// Protocol detection and routing
suspend fun routeProtocol(data: ByteArray): ByteArray {
    val protocol = detectProtocol(data) // QUIC, HTTP, SSH, etc.
    val registry = currentHandlerRegistry<ProtocolId, ProtocolHandler>()
    val handler = registry?.get(protocol)
        ?: throw IllegalStateException("No handler for $protocol")

    val result = handler(data)
    println("Routed via $protocol: ${result.size} bytes")
    return result
}
