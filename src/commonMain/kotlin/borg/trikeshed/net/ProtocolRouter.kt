package borg.trikeshed.net

import borg.trikeshed.context.HandlerRegistry
import borg.trikeshed.context.IoCapability
import borg.trikeshed.context.IoPreference
import borg.trikeshed.context.handlerRegistry
import borg.trikeshed.context.ioCapability
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

// Protocol handlers
typealias ProtocolHandler = suspend (ByteArray) -> ByteArray

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

// Dummy protocol detector
fun detectProtocol(data: ByteArray): String {
    val str = data.decodeToString()
    return when {
        str.contains("QUIC") -> "QUIC"
        str.contains("HTTP") -> "HTTP"
        str.contains("SSH") -> "SSH"
        else -> "UNKNOWN"
    }
}

// Protocol detection and routing
suspend fun routeProtocol(data: ByteArray): ByteArray {
    val protocol = detectProtocol(data) // QUIC, HTTP, SSH, etc.

    val registry = coroutineContext.handlerRegistry<String, ProtocolHandler>()
    val handler = registry?.get(protocol) ?: error("No handler for $protocol")

    val result = handler(data)
    println("Routed via $protocol: ${result.size} bytes")
    return result
}
