package borg.trikeshed.torrent

import borg.trikeshed.cursor.monotonicNanoTime
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.UringIOException
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import kotlinx.coroutines.*
import kotlin.random.Random

/** BEP 15 IPv4 packets; queries/authentication need BEP 41 and are rejected explicitly. */
object TorrentUdpTracker {
    internal fun endpoint(url: String): PeerAddress {
        require(url.startsWith("udp://") && url.length <= 2048)
        require('?' !in url && '#' !in url && '@' !in url) { "UDP tracker query/authentication extensions are unsupported" }
        val target = url.removePrefix("udp://")
        val path = target.substringAfter('/', "")
        require(path.isEmpty() || path == "announce") { "UDP tracker path extensions are unsupported" }
        val authority = target.substringBefore('/')
        val host = authority.substringBefore(':')
        require(authority.count { it == ':' } == 1) { "UDP tracker requires numeric IPv4 and an explicit port" }
        val port = requireNotNull(authority.substringAfter(':').toIntOrNull())
        val peer = PeerAddress(host, port)
        datagramSockaddr(peer)
        return peer
    }

    internal fun connectPacket(transaction: Int): ByteArray = ByteArray(16).also {
        putLong(it, 0, 0x41727101980); putInt(it, 8, 0); putInt(it, 12, transaction)
    }
    internal fun announcePacket(connection: Long, transaction: Int, infoHash: ByteArray, peerId: ByteArray,
                                port: Int, uploaded: Long, downloaded: Long, left: Long,
                                event: TrackerEvent, key: Int): ByteArray {
        require(infoHash.size == 20 && peerId.size == 20 && port in 1..65535)
        require(uploaded >= 0 && downloaded >= 0 && left >= 0)
        return ByteArray(98).also {
            putLong(it, 0, connection); putInt(it, 8, 1); putInt(it, 12, transaction)
            infoHash.copyInto(it, 16); peerId.copyInto(it, 36)
            putLong(it, 56, downloaded); putLong(it, 64, left); putLong(it, 72, uploaded)
            putInt(it, 80, when (event) {
                TrackerEvent.PERIODIC -> 0; TrackerEvent.COMPLETED -> 1
                TrackerEvent.STARTED -> 2; TrackerEvent.STOPPED -> 3
            })
            putInt(it, 88, key); putInt(it, 92, 64)
            it[96] = (port ushr 8).toByte(); it[97] = port.toByte()
        }
    }
    internal fun response(bytes: ByteArray, transaction: Int): TrackerAnnounce {
        require(bytes.size in 20..(20 + 6 * 4096) && (bytes.size - 20) % 6 == 0) { "Invalid UDP tracker peer list" }
        require(peerReadInt(bytes, 0) == 1 && peerReadInt(bytes, 4) == transaction) { "UDP tracker transaction differs" }
        val interval = peerReadInt(bytes, 8).toLong() and 0xffffffffL
        require(interval in 1..86400) { "Invalid UDP tracker interval" }
        val peers = mutableSetOf<PeerAddress>()
        for (at in 20 until bytes.size step 6) {
            val port = ((bytes[at + 4].toInt() and 255) shl 8) or (bytes[at + 5].toInt() and 255)
            if (port != 0) peers.add(PeerAddress((0..3).joinToString(".") { (bytes[at + it].toInt() and 255).toString() }, port))
        }
        return TrackerAnnounce(peers.toSeries(), interval * 1000)
    }

    suspend fun announce(url: String, infoHash: InfoHash, peerId: ByteArray, port: Int,
                         uploaded: Long, downloaded: Long, left: Long, event: TrackerEvent,
                         timeoutMillis: Long = 15000): TrackerAnnounce {
        val peer = endpoint(url)
        require(peerId.size == 20 && port in 1..65535 && uploaded >= 0 && downloaded >= 0 && left >= 0)
        require(timeoutMillis in 1..300000)
        return withTimeout(timeoutMillis) {
            val socket = TorrentDatagram.open(peer)
            try {
                val key = Random.nextInt()
                while (true) {
                    val connectTransaction = Random.nextInt()
                    val connected = requireNotNull(socket.exchange(connectPacket(connectTransaction), 0, connectTransaction))
                    require(connected.size >= 16) { "Truncated UDP tracker connection response" }
                    val connection = readLong(connected, 8)
                    val expires = monotonicNanoTime() + 60_000_000_000
                    val transaction = Random.nextInt()
                    val wire = socket.exchange(announcePacket(connection, transaction, infoHash.wireBytes,
                        peerId, port, uploaded, downloaded, left, event, key), 1, transaction, expires)
                    if (wire != null) return@withTimeout response(wire, transaction)
                    // A connection cookie is valid for one minute. Reconnect before retrying it.
                }
                @Suppress("UNREACHABLE_CODE") error("UDP tracker did not respond")
            } finally { socket.close() }
        }
    }

    private fun putInt(bytes: ByteArray, at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (24 - it * 8)).toByte() } }
    private fun putLong(bytes: ByteArray, at: Int, value: Long) { repeat(8) { bytes[at + it] = (value ushr (56 - it * 8)).toByte() } }
    private fun readLong(bytes: ByteArray, at: Int): Long = (0..7).fold(0L) { value, offset -> (value shl 8) or (bytes[at + offset].toLong() and 255) }
}

/** A bounded request/response UDP descriptor; kernel connection admits only its chosen endpoint. */
internal class TorrentDatagram private constructor(private val ring: TorrentSocketRing, private val fd: Int) {
    suspend fun exchange(request: ByteArray, action: Int, transaction: Int, expires: Long = Long.MAX_VALUE): ByteArray? {
        require(request.size in 1..65507)
        val input = ByteBuffer(65507)
        var nextSend = 0L; var attempt = 0
        while (monotonicNanoTime() < expires) {
            currentCoroutineContext().ensureActive()
            val now = monotonicNanoTime()
            if (now >= nextSend) {
                val sent = ring.execute(UringOp.SEND, fd, ByteBuffer(request))
                if (sent >= 0) {
                    check(sent == request.size) { "Partial UDP tracker datagram SEND" }
                    nextSend = now + (15_000_000_000L shl attempt.coerceAtMost(8)); attempt++
                } else if (sent != -11 && sent != -4) throw UringIOException(UringOp.SEND, sent)
            }
            input.clear()
            val count = ring.execute(UringOp.RECV, fd, input)
            if (count >= 0) {
                check(count <= input.capacity() && input.position() == count) { "Invalid UDP tracker RECV completion" }
                val bytes = input.array().copyOf(count)
                if (count >= 8 && peerReadInt(bytes, 4) == transaction) {
                    val receivedAction = peerReadInt(bytes, 0)
                    if (receivedAction == 3) throw IllegalStateException("UDP tracker: " + bytes.copyOfRange(8, minOf(count, 1032)).decodeToString())
                    if (receivedAction == action) return bytes
                }
            } else if (count != -11 && count != -4) throw UringIOException(UringOp.RECV, count)
            delay(5)
        }
        return null
    }
    suspend fun close() = withContext(NonCancellable) {
        try { if (!ring.closed) {
            val result = ring.execute(UringOp.CLOSE, fd)
            if (result < 0) throw UringIOException(UringOp.CLOSE, result)
        } } finally { ring.close() }
    }
    companion object {
        suspend fun open(peer: PeerAddress): TorrentDatagram {
            val endpoint = ByteBuffer(datagramSockaddr(peer))
            // Keep the descriptor under the calling request, beyond the setup coroutineScope.
            val ring = TorrentSocketRing(CoroutineScope(currentCoroutineContext()))
            return coroutineScope {
            var fd = -1
            val connected = CompletableDeferred<Unit>()
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!connected.isCompleted) ring.close() }
            }
            try {
                fd = ring.execute(UringOp.SOCKET, 2, length = 17, offset = (2 or 0x800).toLong())
                if (fd < 0) throw UringIOException(UringOp.SOCKET, fd)
                while (true) {
                    val result = ring.execute(UringOp.CONNECT, fd, endpoint)
                    if (result == 0 || result == -106) break
                    if (result != -11 && result != -4 && result != -115 && result != -114) throw UringIOException(UringOp.CONNECT, result)
                    delay(5)
                }
                connected.complete(Unit)
                TorrentDatagram(ring, fd)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    if (fd >= 0 && !ring.closed) runCatching { ring.execute(UringOp.CLOSE, fd) }.exceptionOrNull()?.let(failure::addSuppressed)
                    try { ring.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            } finally { withContext(NonCancellable) { cancellation.cancelAndJoin() } }
            }
        }
    }
}

private fun datagramSockaddr(peer: PeerAddress): ByteArray {
    require(peer.port in 1..65535)
    val parts = peer.host.split('.')
    require(parts.size == 4) { "UDP requires a numeric IPv4 endpoint" }
    return sockaddrIpv4(ByteArray(4) { index ->
        val part = parts[index]
        require(part.isNotEmpty() && part.all { it in '0'..'9' }) { "UDP requires a numeric IPv4 endpoint" }
        requireNotNull(part.toIntOrNull()).also { require(it in 0..255) }.toByte()
    }, peer.port)
}
