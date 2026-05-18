package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
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

fun createHttpsHandler(): HtxRequestHandler {
    val providers = borg.trikeshed.userspace.nio.spi.platformNioProviders()
    val channels = providers.filterIsInstance<ChannelOperations>().firstOrNull()
        ?: error("ChannelOperations not available on this platform")
    val reactor = providers.filterIsInstance<ReactorOperations>().firstOrNull()
        ?: error("ReactorOperations not available on this platform")
    return ringHttpsHandler(channels, reactor)
}

/**
 * commonMain HTTPS transport via the unified ring.
 * Uses ChannelHandle.{readv,writev} + ReactorOperations.poll().
 */
fun ringHttpsHandler(channels: ChannelOperations, reactor: ReactorOperations): HtxRequestHandler = { request ->
    val hostPortPath = request.path.removePrefix("https://").removePrefix("http://")
    val hostPort = hostPortPath.substringBefore('/')
    val host = hostPort.substringBefore(':')
    val port = hostPort.substringAfter(":", "").toIntOrNull() ?: 443
    val path = "/" + hostPortPath.substringAfter('/', "")

    val fd = channels.socket(2, 1, 0)
    check(fd >= 0) { "socket failed" }
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
    try {
        check(channels.connect(fd, host, port) >= 0) { "connect failed" }
        reactor.register(fd, setOf(Interest.READ), 1L)

        val sha256 = DefaultSha256(); val x25519 = DefaultX25519()
        val hkdf = DefaultHkdfSha256(sha256); val aes = DefaultAes128Gcm()
        val codec = CommonTlsRecordCodec(aes)
        val hs = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, host)

        val ch = hs.buildClientHello()
        handle.writev(fd, ByteBuffer.wrap(byteArrayOf(0x16, 0x03, 0x03,
            ((ch.size ushr 8) and 0xFF).toByte(), (ch.size and 0xFF).toByte()) + ch))
        handle.submit()

        var done = false; val buf = ByteArray(16384)
        while (!done) {
            reactor.poll(Duration.INFINITE)
            val bb = ByteBuffer.wrap(buf)
            handle.readv(fd, bb)
            handle.submit()
            val results = handle.wait(1)
            val n = results.firstOrNull { it.fd == fd }?.res ?: -1
            if (n <= 0) error("recv failed")
            println("DEBUG: n = $n")
            println("DEBUG: hex = " + buf.take(n).map { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.joinToString(""))
            var p = 0
            while (p + 5 <= n) {
                val rl = ((buf[p+3].toInt() and 0xFF) shl 8) or (buf[p+4].toInt() and 0xFF)
                if (p + 5 + rl > n) break
                val payload = buf.copyOfRange(p+5, p+5+rl)
                val rec = buf.copyOfRange(p, p+5+rl); p += 5 + rl
                when (buf[p - 5 - rl].toInt()) {
                    0x16 -> if (hs.state.name == "CLIENT_HELLO_SENT") hs.processServerHello(payload)
                    0x17 -> codec.decrypt(RecordDirection.SERVER_WRITE, rec)?.let { d ->
                        processHs(d, hs, codec, handle, fd)
                        if (hs.state.name == "CONNECTED") done = true }
                }
            }
        }

        val httpReq = "${request.method} $path HTTP/1.1\r\nHost: $host\r\n" +
            "User-Agent: TrikeShed/1.0\r\nAccept: */*\r\nConnection: close\r\n\r\n" + request.body
        handle.writev(fd, ByteBuffer.wrap(
            codec.encrypt(RecordDirection.CLIENT_WRITE, ContentType.APPLICATION_DATA, httpReq.encodeToByteArray())))
        handle.submit()

        val resp = mutableListOf<Byte>()
        while (true) {
            reactor.poll(Duration.INFINITE)
            val rb = ByteBuffer.wrap(ByteArray(16384))
            handle.readv(fd, rb)
            handle.submit()
            val results = handle.wait(1)
            val n = results.firstOrNull { it.fd == fd }?.res ?: -1
            if (n <= 0) break
            for (i in 0 until n) resp.add(rb.get(i))
        }
        val d = codec.decrypt(RecordDirection.SERVER_WRITE, resp.toByteArray()) ?: error("decrypt failed")
        val t = d.decodeToString()

        var bodyIndex = -1
        for (i in 0 until d.size - 3) {
            if (d[i] == 13.toByte() && d[i+1] == 10.toByte() && d[i+2] == 13.toByte() && d[i+3] == 10.toByte()) {
                bodyIndex = i + 4
                break
            }
        }
        val bodyBytes = if (bodyIndex != -1) d.copyOfRange(bodyIndex, d.size) else byteArrayOf()

        HtxClientMessage(
            status = t.substringAfter(' ').substringBefore(' ').toInt(),
            body = t.substring(t.indexOf("\r\n\r\n") + 4),
            binaryBody = bodyBytes
        )
    } finally {
        reactor.deregister(fd)
    }
}

private suspend fun processHs(data: ByteArray, hs: CommonTlsClientHandshake,
    codec: CommonTlsRecordCodec, handle: ChannelOperations.ChannelHandle, fd: Int) {
    when ((data[0].toInt() and 0xFF)) {
        0x08 -> hs.processEncryptedExtensions(data)
        0x0B -> hs.processCertificate(data)
        0x0F -> hs.processCertificateVerify(data)
        0x14 -> {
            hs.processServerFinished(data)
            handle.writev(fd, ByteBuffer.wrap(codec.encrypt(RecordDirection.CLIENT_WRITE,
                ContentType.HANDSHAKE, hs.buildClientFinished())))
            handle.submit()
        }
    }
}
