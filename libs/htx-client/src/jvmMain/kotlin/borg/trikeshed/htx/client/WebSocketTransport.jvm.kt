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

    override suspend fun invoke(request: HtxClientRequest): HtxClientMessage {
        if (sock == null || sock!!.isClosed) {
            connect(request)
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

        // Read response
        val respBuf = ByteArray(4096)
        val respLen = input!!.read(respBuf)
        val response = ByteArray(respLen).also { respBuf.copyInto(it, 0, 0, respLen) }.decodeToString()

        if (!Rfc6455Handshake.validateUpgradeResponse(response, key)) {
            throw IllegalStateException("WebSocket upgrade failed: $response")
        }
    }

    private suspend fun readMessage(): HtxClientMessage {
        val buf = ByteArray(65536)
        val len = input!!.read(buf)
        if (len <= 0) return HtxClientMessage(status = 500, body = "connection closed")

        val raw = buf.copyOf(len)
        val byteSeries = ByteSeries(raw.toSeries())
        val header = FrameHeader()
        if (!WebSocketFrame.parseFrame(byteSeries, header)) {
            return HtxClientMessage(status = 500, body = "incomplete frame")
        }
        val payload = WebSocketFrame.readPayload(byteSeries, header)
            ?: return HtxClientMessage(status = 500, body = "incomplete payload")

        return HtxClientMessage(
            status = 200,
            body = if (header.opcode == WebSocketFrame.OpCode.TEXT) payload.decodeToString() else "[binary:${payload.size}B]",
        )
    }

    fun send(text: String) {
        val frame = WebSocketFrame.buildFrame(
            opcode = WebSocketFrame.OpCode.TEXT,
            fin = true,
            masked = true,
            maskingKey = ByteArray(4) { (kotlin.random.Random.nextInt() and 0xFF).toByte() },
            payload = text.encodeToByteArray(),
        )
        output!!.write(frame)
        output!!.flush()
    }

    fun close() {
        sock?.close()
    }
}
