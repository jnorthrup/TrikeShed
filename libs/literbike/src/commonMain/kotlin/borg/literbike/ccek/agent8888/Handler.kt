package borg.literbike.ccek.agent8888

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Protocol Handlers - Per-protocol connection handlers
 *
 * This module provides concrete implementations for HTTP, SOCKS5, TLS, and WebSocket.
 */

/**
 * HandlerKey - manages protocol-specific handlers
 */
object HandlerKey : Key<HandlerElement> {
    override fun factory(): HandlerElement = HandlerElement()
}

/**
 * HandlerElement - protocol handler registry and dispatch
 */
class HandlerElement : Element {
    private val httpCount = AtomicLong(0)
    private val socks5Count = AtomicLong(0)
    private val websocketCount = AtomicLong(0)
    private val upnpCount = AtomicLong(0)
    private val unknownCount = AtomicLong(0)

    /**
     * Dispatch handling based on protocol detection
     */
    fun handle(
        protocol: ProtocolDetection,
        clientSocket: Socket,
        buffer: ByteArray
    ): HandlerResult {
        return when (protocol.protocol) {
            Protocol.Http -> {
                httpCount.incrementAndFetch()
                handleHttp(clientSocket, buffer)
            }
            Protocol.Socks5 -> {
                socks5Count.incrementAndFetch()
                handleSocks5(clientSocket, buffer)
            }
            Protocol.WebSocket -> {
                websocketCount.incrementAndFetch()
                handleWebSocket(clientSocket, buffer)
            }
            Protocol.Upnp -> {
                upnpCount.incrementAndFetch()
                handleUpnp(clientSocket, buffer)
            }
            else -> {
                unknownCount.incrementAndFetch()
                HandlerResult.Unsupported
            }
        }
    }

    fun stats(): HandlerStats = HandlerStats(
        http = httpCount.get(),
        socks5 = socks5Count.get(),
        websocket = websocketCount.get(),
        upnp = upnpCount.get(),
        unknown = unknownCount.get()
    )

    override fun keyType(): Any = HandlerKey
    override fun asAny(): Any = this
}

/**
 * Handler statistics
 */
data class HandlerStats(
    val http: Long,
    val socks5: Long,
    val websocket: Long,
    val upnp: Long,
    val unknown: Long
) {
    fun total(): Long = http + socks5 + websocket + upnp + unknown
}

/**
 * Result from protocol handling
 */
sealed class HandlerResult {
    data class Handled(val bytesConsumed: Int) : HandlerResult()
    object NeedMoreData : HandlerResult()
    data class Error(val message: String) : HandlerResult()
    object Unsupported : HandlerResult()
}

// ============================================================================
// HTTP Handler
// ============================================================================

private fun handleHttp(socket: Socket, buffer: ByteArray): HandlerResult {
    val request = buffer.decodeToString()
    val firstLine = request.lineSequence().firstOrNull() ?: return HandlerResult.NeedMoreData

    val parts = firstLine.split(" ")
    if (parts.size < 2) return HandlerResult.Error("Invalid HTTP request")

    val method = parts[0]
    return when {
        method == "CONNECT" -> handleHttpConnect(socket, request)
        else -> handleHttpProxy(socket, request)
    }
}

private fun handleHttpConnect(socket: Socket, request: String): HandlerResult {
    val firstLine = request.lineSequence().firstOrNull() ?: return HandlerResult.NeedMoreData
    val parts = firstLine.split(" ")
    if (parts.size < 2) return HandlerResult.Error("Invalid CONNECT request")

    val target = parts[1]
    val targetAddr = if (target.contains(":")) target else "$target:443"

    return try {
        val remote = Socket()
        remote.connect(
            java.net.InetSocketAddress(
                targetAddr.substringBeforeLast(":"),
                targetAddr.substringAfterLast(":").toIntOrNull() ?: 443
            ),
            5000
        )

        val outputStream = socket.getOutputStream()
        val response = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()
        outputStream.write(response)
        outputStream.flush()

        relayStreams(socket, remote)
        HandlerResult.Handled(request.length)
    } catch (e: Exception) {
        try {
            val outputStream = socket.getOutputStream()
            val response = "HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()
            outputStream.write(response)
            outputStream.flush()
        } catch (_: Exception) {
            // ignore
        }
        HandlerResult.Error("Failed to connect to target: ${e.message}")
    }
}

private fun handleHttpProxy(socket: Socket, request: String): HandlerResult {
    val host = extractHostFromHeaders(request) ?: return run {
        try {
            val outputStream = socket.getOutputStream()
            outputStream.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            outputStream.flush()
        } catch (_: Exception) {
            // ignore
        }
        HandlerResult.Error("No Host header found")
    }

    val targetAddr = "$host:80"

    return try {
        val remote = Socket()
        remote.connect(
            java.net.InetSocketAddress(host, 80),
            5000
        )

        remote.getOutputStream().write(request.toByteArray())
        remote.getOutputStream().flush()

        relayStreams(socket, remote)
        HandlerResult.Handled(request.length)
    } catch (e: Exception) {
        try {
            val outputStream = socket.getOutputStream()
            outputStream.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            outputStream.flush()
        } catch (_: Exception) {
            // ignore
        }
        HandlerResult.Error("Failed to connect to target: ${e.message}")
    }
}

private fun extractHostFromHeaders(request: String): String? {
    for (line in request.lineSequence()) {
        if (line.lowercase().startsWith("host:")) {
            val hostPart = line.substringAfter(":", "").trim()
            val hostWithoutPort = if (hostPart.contains(":")) {
                hostPart.substringBefore(":")
            } else {
                hostPart
            }
            return hostWithoutPort.ifEmpty { null }
        }
    }
    return null
}

// ============================================================================
// SOCKS5 Handler
// ============================================================================

private fun handleSocks5(socket: Socket, buffer: ByteArray): HandlerResult {
    if (buffer.size < 3) return HandlerResult.NeedMoreData

    val version = buffer[0].toInt() and 0xFF
    val nmethods = buffer[1].toInt() and 0xFF

    if (version != 0x05) {
        return HandlerResult.Error("Invalid SOCKS version")
    }

    if (buffer.size < 2 + nmethods) {
        return HandlerResult.NeedMoreData
    }

    // Select method (0x00 = no auth)
    val methods = buffer.sliceArray(2 until 2 + nmethods)
    val selectedMethod = if (methods.any { (it.toInt() and 0xFF) == 0x00 }) 0x00 else 0xFF

    // Send method selection
    val outputStream = socket.getOutputStream()
    outputStream.write(byteArrayOf(0x05, selectedMethod.toByte()))
    outputStream.flush()

    if (selectedMethod == 0xFF) {
        return HandlerResult.Error("No acceptable auth method")
    }

    // Read SOCKS5 request
    val requestBuf = ByteArray(256)
    val inputStream = socket.getInputStream()
    val n = inputStream.read(requestBuf)

    return if (n >= 10) {
        handleSocks5Request(socket, requestBuf.sliceArray(0 until n))
    } else {
        HandlerResult.NeedMoreData
    }
}

private fun handleSocks5Request(socket: Socket, request: ByteArray): HandlerResult {
    if (request.size < 10) return HandlerResult.NeedMoreData

    val version = request[0].toInt() and 0xFF
    val cmd = request[1].toInt() and 0xFF
    val atyp = request[3].toInt() and 0xFF

    if (version != 0x05) {
        return HandlerResult.Error("Invalid SOCKS version in request")
    }

    if (cmd != 0x01) {
        // 0x01 = CONNECT
        val outputStream = socket.getOutputStream()
        outputStream.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        outputStream.flush()
        return HandlerResult.Error("Unsupported SOCKS5 command")
    }

    // Parse target address
    val targetAddr = when (atyp) {
        0x01 -> {
            // IPv4
            if (request.size < 10) return HandlerResult.NeedMoreData
            val ip = "${request[4].toInt() and 0xFF}.${request[5].toInt() and 0xFF}.${request[6].toInt() and 0xFF}.${request[7].toInt() and 0xFF}"
            val port = ((request[8].toInt() and 0xFF) shl 8) or (request[9].toInt() and 0xFF)
            "$ip:$port"
        }
        0x03 -> {
            // Domain name
            val domainLen = request[4].toInt() and 0xFF
            if (request.size < 5 + domainLen + 2) return HandlerResult.NeedMoreData
            val domain = request.sliceArray(5 until 5 + domainLen).decodeToString()
            val portIdx = 5 + domainLen
            val port = ((request[portIdx].toInt() and 0xFF) shl 8) or (request[portIdx + 1].toInt() and 0xFF)
            "$domain:$port"
        }
        else -> {
            val outputStream = socket.getOutputStream()
            outputStream.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outputStream.flush()
            return HandlerResult.Error("Unsupported address type")
        }
    }

    // Connect to target
    return try {
        val host = targetAddr.substringBeforeLast(":")
        val port = targetAddr.substringAfterLast(":").toInt()
        val remote = Socket()
        remote.connect(java.net.InetSocketAddress(host, port), 5000)

        // Send success response
        val outputStream = socket.getOutputStream()
        outputStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        outputStream.flush()

        // Relay data
        relayStreams(socket, remote)
        HandlerResult.Handled(request.size)
    } catch (e: Exception) {
        try {
            val outputStream = socket.getOutputStream()
            outputStream.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outputStream.flush()
        } catch (_: Exception) {
            // ignore
        }
        HandlerResult.Error("Failed to connect to target: ${e.message}")
    }
}

// ============================================================================
// WebSocket Handler
// ============================================================================

private fun handleWebSocket(socket: Socket, buffer: ByteArray): HandlerResult {
    // WebSocket upgrade handling would go here
    // For now, mark as handled but not implemented
    return HandlerResult.Error("WebSocket handling not yet implemented")
}

// ============================================================================
// UPnP Handler
// ============================================================================

private fun handleUpnp(socket: Socket, buffer: ByteArray): HandlerResult {
    val request = buffer.decodeToString()
    if (!request.startsWith("M-SEARCH")) {
        return HandlerResult.Handled(0)
    }

    val localIp = try {
        socket.localAddress.hostAddress ?: "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }

    val response = """
        HTTP/1.1 200 OK
        CACHE-CONTROL: max-age=1800
        EXT:
        LOCATION: http://$localIp:8080/rootdesc.xml
        SERVER: CCEK/1.0 UPnP/1.0
        ST: upnp:rootdevice
        USN: uuid:ccek-001::upnp:rootdevice

    """.trimIndent().replace("\n", "\r\n")

    return try {
        val outputStream = socket.getOutputStream()
        outputStream.write(response.toByteArray())
        outputStream.flush()
        HandlerResult.Handled(buffer.size)
    } catch (e: Exception) {
        HandlerResult.Error("Failed to send UPnP response: ${e.message}")
    }
}

// ============================================================================
// Utility Functions
// ============================================================================

private fun relayStreams(client: Socket, server: Socket) {
    val clientBuf = ByteArray(4096)
    val serverBuf = ByteArray(4096)

    // Use threads for bidirectional relay
    val clientToServer = thread(name = "relay-client-to-server") {
        try {
            val clientIn = client.getInputStream()
            val serverOut = server.getOutputStream()
            while (true) {
                val n = clientIn.read(clientBuf)
                if (n <= 0) break
                serverOut.write(clientBuf, 0, n)
                serverOut.flush()
            }
        } catch (_: Exception) {
            // connection closed
        }
    }

    val serverToClient = thread(name = "relay-server-to-client") {
        try {
            val serverIn = server.getInputStream()
            val clientOut = client.getOutputStream()
            while (true) {
                val n = serverIn.read(serverBuf)
                if (n <= 0) break
                clientOut.write(serverBuf, 0, n)
                clientOut.flush()
            }
        } catch (_: Exception) {
            // connection closed
        }
    }

    clientToServer.join()
    serverToClient.join()
}
