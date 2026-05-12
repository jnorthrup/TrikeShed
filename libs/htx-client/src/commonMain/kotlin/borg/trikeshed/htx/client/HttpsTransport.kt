package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ChannelResult
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.reactor.Interest
import borg.trikeshed.tls.codec.CommonTlsClientHandshake
import borg.trikeshed.tls.codec.CommonTlsRecordCodec
import borg.trikeshed.tls.codec.RecordDirection
import borg.trikeshed.tls.codec.aead.DefaultAes128Gcm
import borg.trikeshed.tls.codec.ecdh.DefaultX25519
import borg.trikeshed.tls.codec.hash.DefaultSha256
import borg.trikeshed.tls.codec.kdf.DefaultHkdfSha256
import borg.trikeshed.tls.record.ContentType
import kotlin.time.Duration

expect fun createHttpsHandler(): HtxRequestHandler

/**
 * commonMain HTTPS transport via the unified ring.
 * Uses ChannelHandle.{readv,writev} + ReactorOperations.poll().
 * 
 * Note: ReactorOperations.poll() is a suspend function, so this handler
 * is only usable from a coroutine context. HtxRequestHandler is already
 * a suspend function type, so this works correctly.
 */
fun ringHttpsHandler(
    channels: ChannelOperations,
    reactor: ReactorOperations,
): HtxRequestHandler = { request: HtxClientRequest ->
    val host = request.path
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore(':')
    val port = request.path
        .substringAfter(":")
        .substringBefore('/')
        .toIntOrNull() ?: 443
    val pathPart = request.path
        .substringAfter(host)
        .substringAfter(port.toString())
        .ifEmpty { "/" }
    val fullPath = if (request.path.contains("?")) {
        request.path.substringAfter(host).substringAfter(port.toString())
    } else {
        val qp = request.headers["X-Query-Params"].orEmpty()
        if (qp.isNotEmpty()) "$pathPart?$qp" else pathPart
    }

    val fd = channels.socket(2, 1, 0)
    check(fd >= 0) { "socket failed" }
    val connResult = channels.connect(fd, host, port)
    check(connResult >= 0) { "connect failed: $connResult" }
    val ring = channels.openChannel()
    val handle = object : ChannelOperations.ChannelHandle {
        override val id get() = fd
        override fun read(b: ByteBuffer, o: Long) = ring.read(b, o)
        override fun write(b: ByteBuffer, o: Long) = ring.write(b, o)
        override fun readv(fd: Int, b: ByteBuffer, userData: Long) = ring.readv(fd, b, userData)
        override fun writev(fd: Int, b: ByteBuffer, userData: Long) = ring.writev(fd, b, userData)
        override fun submit() = ring.submit()
        override fun wait(min: Int) = ring.wait(min)
    }
    reactor.register(fd, setOf(Interest.READ), 1L)

    try {
        val sha256 = DefaultSha256()
        val x25519 = DefaultX25519()
        val hkdf = DefaultHkdfSha256(sha256)
        val aes = DefaultAes128Gcm()
        val codec = CommonTlsRecordCodec(aes)
        val hs = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, host)

        val ch = hs.buildClientHello()
        handle.writev(
            fd, ByteBuffer.wrap(
                byteArrayOf(0x16, 0x03, 0x03,
                    ((ch.size ushr 8) and 0xFF).toByte(),
                    (ch.size and 0xFF).toByte()
                ) + ch
            )
        )

        var done = false
        val buf = ByteArray(16384)
        while (!done) {
            reactor.poll(Duration.INFINITE)
            val bb = ByteBuffer.wrap(buf)
            val n = handle.readv(fd, bb)
            if (n <= 0) error("recv failed")
            var p = 0
            while (p + 5 <= n) {
                val rl = ((buf[p + 3].toInt() and 0xFF) shl 8) or (buf[p + 4].toInt() and 0xFF)
                if (p + 5 + rl > n) break
                val payload = buf.copyOfRange(p + 5, p + 5 + rl)
                val rec = buf.copyOfRange(p, p + 5 + rl)
                p += 5 + rl
                when (buf[p - 5 - rl].toInt()) {
                    0x16 -> if (hs.state.name == "CLIENT_HELLO_SENT") hs.processServerHello(payload)
                    0x17 -> codec.decrypt(RecordDirection.SERVER_WRITE, rec)?.let { d ->
                        processHs(d, hs, codec, handle, fd)
                        if (hs.state.name == "CONNECTED") done = true
                    }
                }
            }
        }

        // Build HTTP request with headers
        val headerLines = request.headers
            .filterKeys { it != "X-Query-Params" }
            .map { "${it.key}: ${it.value}" }
            .joinToString("\r\n")
        val httpReq = "${request.method} $fullPath HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: TrikeShed/1.0\r\n" +
            (if (headerLines.isNotEmpty()) "$headerLines\r\n" else "") +
            "Accept: */*\r\nConnection: close\r\n\r\n" +
            request.body
        handle.writev(fd, ByteBuffer.wrap(
            codec.encrypt(RecordDirection.CLIENT_WRITE, ContentType.APPLICATION_DATA, httpReq.encodeToByteArray())
        ))

        val resp = mutableListOf<Byte>()
        while (true) {
            reactor.poll(Duration.INFINITE)
            val rb = ByteBuffer.wrap(ByteArray(16384))
            val n = handle.readv(fd, rb)
            if (n <= 0) break
            for (i in 0 until n) resp.add(rb.get(i))
        }
        val d = codec.decrypt(RecordDirection.SERVER_WRITE, resp.toByteArray())
            ?: error("decrypt failed")
        val t = d.decodeToString()
        val status = t.substringAfter(' ').substringBefore(' ').toInt()
        val bodyStart = t.indexOf("\r\n\r\n")
        HtxClientMessage(status = status, body = t.substring(bodyStart + 4))
    } finally {
        reactor.deregister(fd)
    }
}

private suspend fun processHs(
    data: ByteArray,
    hs: CommonTlsClientHandshake,
    codec: CommonTlsRecordCodec,
    handle: ChannelOperations.ChannelHandle,
    fd: Int,
) {
    when ((data[0].toInt() and 0xFF)) {
        0x08 -> hs.processEncryptedExtensions(data)
        0x0B -> hs.processCertificate(data)
        0x0F -> hs.processCertificateVerify(data)
        0x14 -> {
            hs.processServerFinished(data)
            handle.writev(fd, ByteBuffer.wrap(
                codec.encrypt(
                    RecordDirection.CLIENT_WRITE,
                    ContentType.HANDSHAKE,
                    hs.buildClientFinished()
                )
            ))
        }
    }
}