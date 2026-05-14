package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.nio.tls.TlsElement
import borg.trikeshed.userspace.nio.tls.TlsSettings
import borg.trikeshed.userspace.reactor.UringReactor

/**
 * Platform actual calls ringHttpsHandler(UringReactor()).
 */
expect fun createHttpsHandler(): HtxRequestHandler

/**
 * commonMain HTTPS handler.
 *
 * Hierarchy — all under reactor.supervisor (the root SupervisorJob):
 *   reactor  (UringReactor)
 *     nioSup (NioSupervisor — provides ChannelOperations via platformNioProviders SPI)
 *     tls    (TlsElement   — the only platform boundary is createTlsEngine inside open())
 *
 * All socket IO goes through reactor.submitWithThrottle { channel.readv / writev }.
 * No JVM types, no platform conditionals.
 */
fun ringHttpsHandler(reactor: UringReactor): HtxRequestHandler = { request: HtxClientRequest ->
    val rawUrl = request.path.toString()
    val noScheme = rawUrl.removePrefix("https://").removePrefix("http://")
    val host = noScheme.substringBefore('/').substringBefore(':')
    check(host.isNotEmpty()) { "empty host in url: $rawUrl" }
    val port = noScheme.substringBefore('/').substringAfter(':', "443").toIntOrNull() ?: 443
    val path = "/" + noScheme.substringAfter('/', "")

    reactor.open()

    // NioSupervisor and TlsElement are children of reactor.supervisor
    val nioSup = NioSupervisor(parentJob = reactor.supervisor)
    nioSup.open()

    val ch: ChannelOperations = nioSup.service<ChannelOperations>()
        ?: error("no ChannelOperations — platformNioProviders() not wired")

    val tls = TlsElement(TlsSettings(serverName = host), parentJob = reactor.supervisor)
    tls.open()

    val handle = ch.openChannel()

    try {
        val fd = reactor.submitWithThrottle { ch.socket(2, 1, 0) }
        check(fd >= 0) { "socket() returned $fd" }

        val cr = reactor.submitWithThrottle { ch.connect(fd, host, port) }
        check(cr >= 0) { "connect($host:$port) returned $cr" }

        // Helpers: raw socket read/write — use ch.recv/send directly (not the file-handle)
        suspend fun rawRead(): ByteArray {
            val buf = ByteBuffer(16384)
            return reactor.submitWithThrottle {
                val n = handle.readv(fd, buf)
                if (n <= 0) error("socket recv failed: $n")
                buf.array().copyOf(n)
            }
        }

        suspend fun rawWrite(data: ByteArray) {
            reactor.submitWithThrottle {
                handle.writev(fd, ByteBuffer.wrap(data))
            }
        }

        // TLS handshake — entirely through TlsElement; no platform types
        tls.handshake(reader = { rawRead() }, writer = { rawWrite(it) })

        // HTTP/1.1 request
        val extraHeaders = request.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        val reqBody = request.body.toString()
        val reqBodyBytes = reqBody.encodeToByteArray()
        val httpReq = buildString {
            append("${request.method} $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("User-Agent: TrikeShed/1.0\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n")
            if (extraHeaders.isNotEmpty()) append(extraHeaders)
            if (reqBody.isNotEmpty()) append("Content-Length: ${reqBodyBytes.size}\r\n")
            append("\r\n")
            append(reqBody)
        }

        rawWrite(tls.wrap(httpReq.encodeToByteArray()))

        // Read and decrypt response records
        val headerBuf = StringBuilder()
        val bodyBytes = mutableListOf<Byte>()
        val respHeaders = mutableMapOf<CharSequence, CharSequence>()
        var status = 0
        var headersDone = false

        while (true) {
            val raw = try { rawRead() } catch (_: Exception) { break }
            var p = 0
            while (p + 5 <= raw.size) {
                val ct = raw[p].toInt() and 0xFF
                val rl = ((raw[p + 3].toInt() and 0xFF) shl 8) or (raw[p + 4].toInt() and 0xFF)
                if (p + 5 + rl > raw.size) break
                val record = raw.copyOfRange(p, p + 5 + rl)
                p += 5 + rl
                if (ct != 0x17) continue  // skip alerts / ChangeCipherSpec

                val plain = tls.unwrap(record)

                if (!headersDone) {
                    headerBuf.append(plain.decodeToString())
                    val he = headerBuf.indexOf("\r\n\r\n")
                    if (he >= 0) {
                        headersDone = true
                        parseResponse(headerBuf.substring(0, he), respHeaders) { status = it }
                        headerBuf.substring(he + 4).encodeToByteArray().forEach { bodyBytes.add(it) }
                    }
                } else {
                    plain.forEach { bodyBytes.add(it) }
                }
            }
        }

        HtxClientMessage(status = status, headers = respHeaders, body = bodyBytes.toByteArray().decodeToString())
    } finally {
        tls.close()
        nioSup.close()
        ch.close(0)
    }
}

private fun parseResponse(
    headerText: String,
    out: MutableMap<CharSequence, CharSequence>,
    onStatus: (Int) -> Unit,
) {
    val lines = headerText.split("\r\n")
    lines.firstOrNull()?.split(" ", limit = 3)?.getOrNull(1)?.toIntOrNull()?.let(onStatus)
    for (line in lines.drop(1)) {
        val c = line.indexOf(':')
        if (c > 0) out[line.substring(0, c).trim()] = line.substring(c + 1).trim()
    }
}
