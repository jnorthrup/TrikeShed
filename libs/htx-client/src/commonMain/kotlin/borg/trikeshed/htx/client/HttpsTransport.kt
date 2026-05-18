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

expect fun createHttpsHandler(): HtxRequestHandler

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
        val hs = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, host, listOf("http/1.1"))

        val ch = hs.buildClientHello()
        handle.writev(fd, ByteBuffer.wrap(byteArrayOf(0x16, 0x03, 0x03,
            ((ch.size ushr 8) and 0xFF).toByte(), (ch.size and 0xFF).toByte()) + ch))
        handle.submit()

        var done = false
        val accumulator = mutableListOf<Byte>()
        val readBuf = ByteArray(16384)
        while (!done) {
            reactor.poll(Duration.INFINITE)
            val bb = ByteBuffer.wrap(readBuf)
            handle.readv(fd, bb)
            handle.submit()
            val results = handle.wait(1)
            val n = results.firstOrNull { it.fd == fd }?.res ?: -1
            println("DEBUG recv: n = $n, hs.state = ${hs.state.name}, accumulator.size = ${accumulator.size}")
            if (n <= 0) error("recv failed: n = $n, state = ${hs.state.name}")
            for (i in 0 until n) {
                accumulator.add(readBuf[i])
            }

            var p = 0
            while (p + 5 <= accumulator.size) {
                val rl = ((accumulator[p+3].toInt() and 0xFF) shl 8) or (accumulator[p+4].toInt() and 0xFF)
                if (p + 5 + rl > accumulator.size) break
                val rec = ByteArray(5 + rl) { idx -> accumulator[p + idx] }
                val payload = rec.copyOfRange(5, 5 + rl)
                p += 5 + rl

                when (rec[0].toInt()) {
                    0x16 -> if (hs.state.name == "CLIENT_HELLO_SENT") hs.processServerHello(payload)
                    0x17 -> codec.decrypt(RecordDirection.SERVER_WRITE, rec)?.let { d ->
                        processHs(d, hs, codec, handle, fd)
                        if (hs.state.name == "CONNECTED") done = true }
                }
            }
            if (p > 0) {
                repeat(p) { accumulator.removeAt(0) }
            }
        }

        val httpReq = "${request.method} $path HTTP/1.1\r\nHost: $host\r\n" +
            "User-Agent: TrikeShed/1.0\r\nAccept: */*\r\nConnection: close\r\n\r\n" + request.body
        handle.writev(fd, ByteBuffer.wrap(
            codec.encrypt(RecordDirection.CLIENT_WRITE, ContentType.APPLICATION_DATA, httpReq.encodeToByteArray())))
        handle.submit()

        val responseBytes = mutableListOf<Byte>()
        val httpAccumulator = mutableListOf<Byte>()
        val httpReadBuf = ByteArray(16384)
        while (true) {
            reactor.poll(Duration.INFINITE)
            val rb = ByteBuffer.wrap(httpReadBuf)
            handle.readv(fd, rb)
            handle.submit()
            val results = handle.wait(1)
            val n = results.firstOrNull { it.fd == fd }?.res ?: -1
            if (n <= 0) break
            for (i in 0 until n) {
                httpAccumulator.add(httpReadBuf[i])
            }

            var hp = 0
            var doneReading = false
            while (hp + 5 <= httpAccumulator.size) {
                val rl = ((httpAccumulator[hp+3].toInt() and 0xFF) shl 8) or (httpAccumulator[hp+4].toInt() and 0xFF)
                if (hp + 5 + rl > httpAccumulator.size) break
                val rec = ByteArray(5 + rl) { idx -> httpAccumulator[hp + idx] }
                hp += 5 + rl

                if (rec[0].toInt() == 0x17) {
                    val decrypted = codec.decrypt(RecordDirection.SERVER_WRITE, rec)
                    if (decrypted != null) {
                        val contentType = (codec as? CommonTlsRecordCodec)?.lastDecryptedContentType
                        if (contentType == ContentType.APPLICATION_DATA) {
                            for (b in decrypted) responseBytes.add(b)
                        } else if (contentType == ContentType.ALERT) {
                            doneReading = true
                            break
                        }
                    }
                }
            }
            if (hp > 0) {
                repeat(hp) { httpAccumulator.removeAt(0) }
            }
            if (doneReading) break
        }
        val d = responseBytes.toByteArray()
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
    var p = 0
    while (p + 4 <= data.size) {
        val type = data[p].toInt() and 0xFF
        val len = ((data[p+1].toInt() and 0xFF) shl 16) or
                  ((data[p+2].toInt() and 0xFF) shl 8) or
                   (data[p+3].toInt() and 0xFF)
        val msg = ByteArray(4 + len) { idx -> data[p + idx] }
        p += 4 + len
        when (type) {
            0x08 -> hs.processEncryptedExtensions(msg)
            0x0B -> hs.processCertificate(msg)
            0x0F -> hs.processCertificateVerify(msg)
            0x14 -> {
                hs.processServerFinished(msg)
                val finished = hs.buildClientFinished()
                val encrypted = codec.encrypt(RecordDirection.CLIENT_WRITE, ContentType.HANDSHAKE, finished)
                hs.installClientApplicationWriteKey()
                handle.writev(fd, ByteBuffer.wrap(encrypted))
                handle.submit()
            }
        }
    }
}
