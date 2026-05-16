@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.close

/**
 * macOS native SCTP transport via ring reactor raw IP + pure-Kotlin SCTP.
 *
 * Raw IP socket → PosixChannelOperations (pread/pwrite reactor path)
 * SCTP framing → SctpElement (INIT/COOKIE handshake, stream associations)
 *
 * Note: macOS doesn't ship with kernel SCTP (IPPROTO_SCTP).
 * Fallback: SCTP over UDP encapsulation (RFC 6951).
 */
actual fun createSctpHandler(): HtxRequestHandler = NativeSctpHandler

private val NativeSctpHandler: HtxRequestHandler = { request ->
    val url = ""+request.path
    val host = url.removePrefix("sctp://").substringBefore(':')
    val port = url.substringAfter(":").substringBefore('/').toIntOrNull() ?: 9899

    val ops = PosixChannelOperations()
    // SCTP over UDP encapsulation — macOS doesn't support IPPROTO_SCTP
    val fd = ops.socket(AF_INET, SOCK_DGRAM, 0)
    check(fd >= 0) { "UDP socket (for SCTP encapsulation) failed" }
    try {
        check(ops.connect(fd, host, port) >= 0) { "UDP connect to $host:$port failed" }
        val handle = ops.openChannel()

        // Build SCTP INIT chunk over UDP encapsulation
        val sctpElement =  SctpElement()
        sctpElement.open()
        val assoc = sctpElement.connect(host, port)

        // Generate random initiate tag and TSN (RFC 4960 §5.2.1)
        val randomTag = kotlin.random.Random.nextInt().toUInt()
        val randomTsn = kotlin.random.Random.nextInt().toUInt()
        val initChunk =  SctpInitChunk(
            initiateTag = randomTag,
            aRwnd = 131072u,
            outboundStreams = 10u,
            inboundStreams = 10u,
            initialTsn = randomTsn,
        )
        handle.writev(fd, borg.trikeshed.userspace.nio.ByteBuffer.wrap(initChunk.encode()))
        handle.submit()
        val results = handle.wait()

        HtxClientMessage(
            status = if (results.any { it.res > 0 }) 200 else 503,
            body = "SCTP INIT sent to $host:$port (assoc=${assoc.a})",
        )
    } finally {
        close(fd)
    }
}
