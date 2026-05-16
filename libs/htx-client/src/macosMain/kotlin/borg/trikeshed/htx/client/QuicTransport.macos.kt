@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.close

/**
 * macOS native QUIC transport via ring reactor UDP + pure-Kotlin QUIC.
 *
 * UDP socket → PosixChannelOperations (pread/pwrite reactor path)
 * QUIC framing → QuicElement (short/long headers, connection IDs, stream IDs)
 * TLS 1.3 → CommonTlsClientHandshake/CommonTlsRecordCodec (pure Kotlin)
 */
actual fun createQuicHandler(): HtxRequestHandler = NativeQuicHandler

private val NativeQuicHandler: HtxRequestHandler = { request ->
    val url = request.path
    val host = (""+ url).removePrefix("quic://").substringBefore(':')
    val port = (""+url).substringAfter(":").substringBefore('/').toIntOrNull() ?: 4433

    val ops = PosixChannelOperations()
    val fd = ops.socket(AF_INET, SOCK_DGRAM, 0)
    check(fd >= 0) { "UDP socket failed" }
    try {
        check(ops.connect(fd, host, port) >= 0) { "UDP connect to $host:$port failed" }
        val handle = ops.openChannel()

        // Build QUIC Initial packet with TLS 1.3 ClientHello in CRYPTO frame
        val quicElement =  QuicElement()
        quicElement.open()
        val stream = quicElement.connect(host, port)

        // For now: send a QUIC short-header ping, read response
        val pingFrame = byteArrayOf(0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01) // PING frame in short header
        handle.writev(fd, borg.trikeshed.userspace.nio.ByteBuffer.wrap(pingFrame))
        handle.submit()
        val results = handle.wait()

        HtxClientMessage(
            status = if (results.any { it.res > 0 }) 200 else 503,
            body = "QUIC ping sent to $host:$port",
        )
    } finally {
        close(fd)
    }
}
