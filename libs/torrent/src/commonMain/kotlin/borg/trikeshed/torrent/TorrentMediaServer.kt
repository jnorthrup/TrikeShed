package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.*

/**
 * HTTP Range server that serves a [StreamingTorrent] as a seekable media stream.
 *
 * FFmpeg/FFplay integration:
 *   ffplay -i http://localhost:6800/stream
 *   ffmpeg -i http://localhost:6800/stream -c copy output.mp4
 *
 * The server handles HTTP Range requests, mapping them to [StreamingTorrent.read],
 * which prioritizes piece downloads around the requested byte range.
 */
class TorrentMediaServer(
    private val streaming: StreamingTorrent,
    private val fileName: CharSequence = "stream",
    private val port: Int = 6800,
    parentJob: kotlinx.coroutines.Job? = null,
) : AsyncContextElement(parentJob = parentJob) {
    companion object Key : AsyncContextKey<TorrentMediaServer>() {
        const val ACCEPT_KEY: Long = 0
        const val READ_KEY: Long = 1
    }
    override val key: AsyncContextKey<TorrentMediaServer> get() = Key

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        streaming.open()
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            streaming.close()
            supervisor.cancel()
            super.close()
        }
    }

    /**
     * Serve HTTP Range requests via the ring reactor.
     * The caller must provide [channels] and [reactor] as TrikeShed SPI implementations.
     */
    suspend fun serve(
        channels: borg.trikeshed.userspace.nio.channels.spi.ChannelOperations,
        reactor: borg.trikeshed.userspace.nio.channels.spi.ReactorOperations,
    ) = withContext(supervisor) {
        requireState(ElementState.ACTIVE)
        val serverFd = channels.socket(2, 1, 0)
        check(serverFd >= 0) { "socket failed" }
        channels.bind(serverFd, port)
        channels.listen(serverFd, 128)
        reactor.register(serverFd, setOf(borg.trikeshed.userspace.reactor.Interest.READ), ACCEPT_KEY)

        val ring = channels.openChannel()
        ring.readv(serverFd, borg.trikeshed.userspace.nio.ByteBuffer.allocate(64))
        ring.submit()

        while (isActive) {
            val results = ring.wait()
            for (r in results) {
                when (r.userData) {
                    ACCEPT_KEY -> {
                        ring.readv(serverFd, borg.trikeshed.userspace.nio.ByteBuffer.allocate(64))
                        val clientFd = r.res
                        if (clientFd >= 0) {
                            reactor.register(clientFd, setOf(borg.trikeshed.userspace.reactor.Interest.READ), READ_KEY)
                            // Store client state: buffer for accumulating HTTP request
                            clientBuffers[clientFd] = StringBuilder()
                        }
                    }
                    READ_KEY -> {
                        if (r.res <= 0) { reactor.deregister(r.fd); clientBuffers.remove(r.fd); continue }
                        handleClientRead(r.fd, ring)
                    }
                }
            }
            ring.submit()
        }
        reactor.deregister(serverFd)
    }

    // ── HTTP handling ─────────────────────────────────────────────

    private val clientBuffers = LinkedHashMap<Int, StringBuilder>()

    private suspend fun handleClientRead(fd: Int, ring: borg.trikeshed.userspace.nio.channels.spi.ChannelOperations.ChannelHandle) {
        // Simplified HTTP parsing — extract method, path, and Range header
        val sb = clientBuffers[fd] ?: return
        // Read request bytes from buffer (in production, read from ring result)
        val request = sb.toString()
        if (!request.contains("\r\n\r\n")) {
            // Need more data — re-enqueue read
            ring.readv(fd, borg.trikeshed.userspace.nio.ByteBuffer.allocate(4096))
            return
        }

        val lines = request.split("\r\n")
        val firstLine = lines.firstOrNull() ?: return
        val parts = firstLine.split(' ')
        val method = parts.getOrNull(0) ?: "GET"
        val path = parts.getOrNull(1) ?: "/"

        if (method != "GET" && method != "HEAD") {
            writeResponse(fd, ring, 405, "Method Not Allowed", null)
            return
        }

        // Parse Range header
        val rangeHeader = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
        val range = parseRange(rangeHeader)

        val totalSize = streaming.totalLength

        when {
            range != null -> {
                val start = range.first
                val end = minOf(range.second, totalSize - 1)
                val length = (end - start + 1).toInt()

                // Seek to requested position — reprioritizes pieces
                streaming.seek(start)

                // Read the requested bytes (may block until available)
                val data = streaming.read(start, length)
                val statusLine = "HTTP/1.1 206 Partial Content"
                val headers = buildString {
                    append("Content-Type: ${detectContentType(fileName)}\r\n")
                    append("Content-Range: bytes $start-$end/$totalSize\r\n")
                    append("Content-Length: ${data.size}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n")
                }
                val response = "$statusLine\r\n$headers\r\n"
                ring.writev(fd, borg.trikeshed.userspace.nio.ByteBuffer.wrap(response.encodeToByteArray() + data))
            }
            else -> {
                // Full file request — respond with 200 and total size
                // FFplay typically issues HEAD or small initial read first
                val statusLine = "HTTP/1.1 200 OK"
                val headers = buildString {
                    append("Content-Type: ${detectContentType(fileName)}\r\n")
                    append("Content-Length: $totalSize\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n")
                }
                val response = "$statusLine\r\n$headers\r\n"
                ring.writev(fd, borg.trikeshed.userspace.nio.ByteBuffer.wrap(response.encodeToByteArray()))
            }
        }
        ring.submit()
        clientBuffers.remove(fd)
    }

    private fun writeResponse(fd: Int, ring: borg.trikeshed.userspace.nio.channels.spi.ChannelOperations.ChannelHandle, status: Int, message: CharSequence, body: CharSequence?) {
        val statusLine = "HTTP/1.1 $status $message"
        val bodyStr = body ?: ""
        val headers = buildString {
            append("Content-Length: ${bodyStr.encodeToByteArray().size}\r\n")
            append("Connection: close\r\n")
        }
        val response = "$statusLine\r\n$headers\r\n$bodyStr"
        ring.writev(fd, borg.trikeshed.userspace.nio.ByteBuffer.wrap(response.encodeToByteArray()))
        ring.submit()
    }

    private fun parseRange(header: CharSequence?): Pair<Long, Long>? {
        if (header == null) return null
        val eq = header.indexOf('=')
        if (eq < 0) return null
        val value = header.substring(eq + 1).trim()
        val dash = value.indexOf('-')
        if (dash < 0) return null
        val start = value.substring(0, dash).toLongOrNull() ?: return null
        val end = value.substring(dash + 1).toLongOrNull() ?: (streaming.totalLength - 1)
        return start to end
    }

    // ── MIME detection ────────────────────────────────────────────

    private fun detectContentType(name: CharSequence): CharSequence {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "ogg", "ogv" -> "video/ogg"
            "flv" -> "video/x-flv"
            "ts" -> "video/mp2t"
            "m3u8" -> "application/vnd.apple.mpegurl"
            else -> "application/octet-stream"
        }
    }

}
