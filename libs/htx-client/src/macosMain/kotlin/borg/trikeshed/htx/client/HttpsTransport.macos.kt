@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
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
import platform.posix.*

/**
 * macOS native HTTPS transport via reactor.
 * Non-blocking socket IO through PosixReactorOperations (poll).
 * Unified: handle.read(buffer, -1L) → recv for sockets.
 */
actual fun createHttpsHandler(): HtxRequestHandler = NativeHttpsHandler

private val NativeHttpsHandler: HtxRequestHandler = { request ->
    val url = request.path
    val host = url.removePrefix("https://").removePrefix("http://").substringBefore(':')
    val port = url.substringAfter(":").substringBefore('/').toIntOrNull() ?: 443
    val path = url.substringAfter(host).substringAfter(port.toString()).ifEmpty { "/" }

    val ops = PosixChannelOperations()
    val reactor = PosixReactorOperations()
    val fd = ops.socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "socket failed" }
    val handle = ops.handleFor(fd)
    try {
        check(ops.connect(fd, host, port) >= 0) { "connect to $host:$port failed" }
        // Non-blocking — reactor polls readiness
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) or O_NONBLOCK)
        reactor.register(fd, setOf(Interest.READ), 1L)

        val sha256 = DefaultSha256()
        val x25519 = DefaultX25519()
        val hkdf = DefaultHkdfSha256(sha256)
        val aes = DefaultAes128Gcm()
        val codec = CommonTlsRecordCodec(aes)
        val hs = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, host)

        // ClientHello
        val ch = hs.buildClientHello()
        val record = byteArrayOf(0x16, 0x03, 0x03,
            ((ch.size ushr 8) and 0xFF).toByte(), (ch.size and 0xFF).toByte()) + ch
        handle.write(ByteBuffer.wrap(record), -1L)

        // Handshake — poll-loop
        var done = false
        val buf = ByteArray(16384)
        while (!done) {
            val signals = reactor.poll(Duration.INFINITE)
            for (signal in signals) {
                if (Interest.READ !in signal.ready) continue
                val bb = ByteBuffer.wrap(buf)
                val n = handle.read(bb, -1L)
                if (n <= 0) { if (n < 0 && errno == EAGAIN) continue; error("recv failed") }
                var pos = 0
                while (pos + 5 <= n) {
                    val rtype = buf[pos]
                    val rlen = ((buf[pos + 3].toInt() and 0xFF) shl 8) or (buf[pos + 4].toInt() and 0xFF)
                    if (pos + 5 + rlen > n) break
                    val payload = buf.copyOfRange(pos + 5, pos + 5 + rlen)
                    val recordBytes = buf.copyOfRange(pos, pos + 5 + rlen)
                    pos += 5 + rlen
                    when (rtype.toInt()) {
                        0x16 -> if (hs.state.name == "CLIENT_HELLO_SENT") hs.processServerHello(payload)
                        0x17 -> {
                            val dec = codec.decrypt(RecordDirection.SERVER_WRITE, recordBytes) ?: continue
                            processHs(dec, hs, codec, handle)
                            if (hs.state.name == "CONNECTED") done = true
                        }
                    }
                }
            }
        }

        // HTTP request
        val httpReq = "${request.method} $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: TrikeShed/1.0\r\nAccept: */*\r\n" +
            "Connection: close\r\n\r\n" + request.body
        val enc = codec.encrypt(RecordDirection.CLIENT_WRITE, ContentType.APPLICATION_DATA, httpReq.encodeToByteArray())
        handle.write(ByteBuffer.wrap(enc), -1L)

        // Read response — poll-loop
        val resp = mutableListOf<Byte>()
        while (true) {
            val signals = reactor.poll(Duration.INFINITE)
            var gotData = false
            for (signal in signals) {
                if (Interest.READ !in signal.ready) continue
                val bb = ByteBuffer.wrap(ByteArray(16384))
                val n = handle.read(bb, -1L)
                if (n <= 0) { if (n < 0 && errno == EAGAIN) continue; break }
                gotData = true
                for (i in 0 until n) resp.add(bb.get(i))
            }
            if (!gotData) break
        }
        val decrypted = codec.decrypt(RecordDirection.SERVER_WRITE, resp.toByteArray())
            ?: error("response decrypt failed")
        val text = decrypted.decodeToString()
        val status = text.substringAfter(' ').substringBefore(' ').toInt()
        val bodyStart = text.indexOf("\r\n\r\n") + 4

        HtxClientMessage(status = status, body = text.substring(bodyStart))
    } finally {
        reactor.deregister(fd)
        close(fd)
    }
}

private suspend fun processHs(data: ByteArray, hs: CommonTlsClientHandshake, codec: CommonTlsRecordCodec, handle: ChannelOperations.ChannelHandle) {
    when ((data[0].toInt() and 0xFF)) {
        0x08 -> hs.processEncryptedExtensions(data)
        0x0B -> hs.processCertificate(data)
        0x0F -> hs.processCertificateVerify(data)
        0x14 -> {
            hs.processServerFinished(data)
            val cf = hs.buildClientFinished()
            handle.write(ByteBuffer.wrap(codec.encrypt(RecordDirection.CLIENT_WRITE, ContentType.HANDSHAKE, cf)), -1L)
        }
    }
}
