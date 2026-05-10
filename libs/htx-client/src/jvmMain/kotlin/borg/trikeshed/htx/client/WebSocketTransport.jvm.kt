package borg.trikeshed.htx.client

import borg.trikeshed.lib.*
import borg.trikeshed.ws.FrameHeader
import borg.trikeshed.ws.Rfc6455Handshake
import borg.trikeshed.ws.WebSocketFrame

actual fun createWsHandler(): HtxRequestHandler = JvmWsHandler()

/**
 * JVM WebSocket transport using raw Socket + javax.net.ssl.SSLSocket + manual RFC 6455.
 *
 * `java.net.http.WebSocket` (Java 11+) does the handshake internally but doesn't
 * expose custom `Sec-WebSocket-Protocol` or `Origin` header control easily.
 * We fall back to raw socket + manual handshake so we control every header.
 */
class JvmWsHandler : HtxRequestHandler {

    private var sock: java.net.Socket? = null
    private var input: java.io.InputStream? = null
    private var output: java.io.OutputStream? = null
    private var pending = ByteArray(0)

    override suspend fun invoke(request: HtxClientRequest): HtxClientMessage {
        if (sock == null || sock!!.isClosed) {
            connect(request)
        }
        if (request.body.isNotBlank()) {
            send(request.body)
        }
        return readMessage()
    }

    private fun connect(request: HtxClientRequest) {
        val uri = request.path.removePrefix("wss://").removePrefix("ws://")
        val slash = uri.indexOf('/')
        val hostPort = if (slash >= 0) uri.substring(0, slash) else uri
        val wsPath = if (slash >= 0) uri.substring(slash) else "/"
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "443").toIntOrNull()
            ?: if (request.path.startsWith("wss://")) 443 else 80

        val isTls = request.path.startsWith("wss://")

        sock = if (isTls) {
            val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val sslSock = factory.createSocket(host, port) as javax.net.ssl.SSLSocket
            sslSock.startHandshake()
            sslSock
        } else {
            java.net.Socket(host, port)
        }

        input = sock!!.getInputStream()
        output = sock!!.getOutputStream()

        // RFC 6455 handshake
        val key = Rfc6455Handshake.generateKey()
        val handshake = Rfc6455Handshake.buildUpgradeRequest(
            path = wsPath,
            host = "$host:$port",
            key = key,
        )

        output!!.write(handshake.encodeToByteArray())
        output!!.flush()

        // Read response headers; preserve any post-header bytes for frame parsing.
        val responseBytes = java.io.ByteArrayOutputStream()
        while (true) {
            val chunk = ByteArray(4096)
            val read = input!!.read(chunk)
            if (read <= 0) throw IllegalStateException("WebSocket upgrade failed: connection closed during handshake")
            responseBytes.write(chunk, 0, read)
            val bytes = responseBytes.toByteArray()
            val headerEnd = findHeaderEnd(bytes)
            if (headerEnd >= 0) {
                val headerBytes = bytes.copyOfRange(0, headerEnd)
                pending = if (headerEnd < bytes.size) bytes.copyOfRange(headerEnd, bytes.size) else byteArrayOf()
                val response = headerBytes.decodeToString()
                if (!Rfc6455Handshake.validateUpgradeResponse(response, key)) {
                    throw IllegalStateException("WebSocket upgrade failed: $response")
                }
                return
            }
        }
    }

    private suspend fun readMessage(): HtxClientMessage {
        while (true) {
            val byteSeries = ByteSeries(pending)
            val header = FrameHeader()
            if (!WebSocketFrame.parseFrame(byteSeries, header)) {
                if (!readIntoPending()) return HtxClientMessage(status = 500, body = "connection closed")
                continue
            }
            val payload = WebSocketFrame.readPayload(byteSeries, header)
            if (payload == null) {
                if (!readIntoPending()) return HtxClientMessage(status = 500, body = "connection closed")
                continue
            }

            pending = if (byteSeries.pos < pending.size) pending.copyOfRange(byteSeries.pos, pending.size) else byteArrayOf()

            when (header.opcode) {
                WebSocketFrame.OpCode.PING -> {
                    sendFrame(WebSocketFrame.OpCode.PONG, payload)
                    continue
                }
                WebSocketFrame.OpCode.PONG -> continue
                WebSocketFrame.OpCode.CLOSE -> return HtxClientMessage(status = 500, body = "connection closed")
                WebSocketFrame.OpCode.TEXT -> return HtxClientMessage(status = 200, body = payload.decodeToString())
                WebSocketFrame.OpCode.BINARY,
                WebSocketFrame.OpCode.CONTINUATION,
                -> return HtxClientMessage(status = 200, body = "[binary:${payload.size}B]")
            }
        }
    }

    fun send(text: String) {
        sendFrame(WebSocketFrame.OpCode.TEXT, text.encodeToByteArray())
    }

    private fun sendFrame(opcode: WebSocketFrame.OpCode, payload: ByteArray) {
        val frame = WebSocketFrame.buildFrame(
            opcode = opcode,
            fin = true,
            masked = true,
            maskingKey = ByteArray(4) { (kotlin.random.Random.nextInt() and 0xFF).toByte() },
            payload = payload,
        )
        output!!.write(frame)
        output!!.flush()
    }

    private fun readIntoPending(): Boolean {
        val buf = ByteArray(65536)
        val len = input!!.read(buf)
        if (len <= 0) return false
        val raw = buf.copyOf(len)
        pending = if (pending.isEmpty()) raw else pending + raw
        return true
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        if (bytes.size < 4) return -1
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() && bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()) {
                return i + 4
            }
        }
        return -1
    }

    fun close() {
        sock?.close()
    }
}
