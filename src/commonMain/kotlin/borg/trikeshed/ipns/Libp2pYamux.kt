package borg.trikeshed.ipns

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** One outbound RPC stream per owned session; inbound streams are explicitly reset. */
internal class Libp2pYamuxStream(private val wire: Libp2pStream, private val timeout: Long) : Libp2pStream {
    private val mutex = Mutex()
    private var started = false
    private var closed = false
    private var eof = false
    private var sendWindow = 262144L
    private var receiveWindow = 262144L
    private var pending = byteArrayOf()

    private suspend fun frame(type: Int, flags: Int, stream: Long, length: Long, data: ByteArray = byteArrayOf()) {
        val header = ByteArray(12)
        header[1] = type.toByte(); header[2] = (flags ushr 8).toByte(); header[3] = flags.toByte()
        for (i in 0..3) { header[4 + i] = (stream ushr ((3 - i) * 8)).toByte(); header[8 + i] = (length ushr ((3 - i) * 8)).toByte() }
        wire.write(header + data)
    }

    private suspend fun receive() {
        val header = wire.exact(12)
        require(header[0] == 0.toByte()) { "Unsupported yamux version" }
        val type = header[1].toInt() and 255
        val flags = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        require(flags and 15.inv() == 0)
        fun uint(at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (header[at + i].toLong() and 255) }
        val id = uint(4)
        val length = uint(8)
        when (type) {
            0, 1 -> {
                require(id != 0L)
                val bytes = if (type == 0) {
                    require(length <= 262144L) { "Yamux frame exceeds receive bound" }
                    wire.exact(length.toInt())
                } else byteArrayOf()
                if (id != 1L) {
                    if (flags and 8 == 0) frame(1, 8, id, 0)
                    return
                }
                check(flags and 8 == 0) { "Peer reset yamux stream" }
                require(flags and 1 == 0) { "Peer reused outbound yamux stream ID" }
                if (type == 0) {
                    require(length <= receiveWindow && pending.size + bytes.size <= 262144) { "Peer exceeded yamux receive window" }
                    receiveWindow -= length
                    pending += bytes
                } else {
                    require(length <= 16777216L && sendWindow + length <= 16777216L) { "Yamux send window exceeds bound" }
                    sendWindow += length
                }
                if (flags and 4 != 0) eof = true
            }
            2 -> {
                require(id == 0L && flags in 1..2)
                if (flags == 1) frame(2, 2, 0, length)
            }
            3 -> { require(id == 0L && flags == 0); error("Peer closed yamux session: $length") }
            else -> error("Unknown yamux frame type $type")
        }
    }

    override suspend fun read(maxBytes: Int): ByteArray = withTimeout(timeout) {
        require(maxBytes in 1..1048576)
        mutex.withLock {
            check(!closed)
            while (pending.isEmpty() && !eof) receive()
            if (pending.isEmpty()) return@withLock byteArrayOf()
            val count = minOf(maxBytes, pending.size)
            val result = pending.copyOfRange(0, count)
            pending = pending.copyOfRange(count, pending.size)
            receiveWindow += count
            frame(1, 0, 1, count.toLong())
            result
        }
    }

    override suspend fun write(bytes: ByteArray) = withTimeout(timeout) {
        require(bytes.size <= 1048576)
        mutex.withLock {
            check(!closed)
            var at = 0
            while (at < bytes.size) {
                while (sendWindow == 0L) receive()
                val count = minOf(bytes.size - at, 65536, sendWindow.toInt())
                frame(0, if (!started) 1 else 0, 1, count.toLong(), bytes.copyOfRange(at, at + count))
                started = true
                sendWindow -= count
                at += count
            }
        }
    }

    override suspend fun close() = mutex.withLock {
        if (!closed) {
            closed = true
            runCatching { withTimeoutOrNull(1000) { if (started) frame(1, 4, 1, 0); frame(3, 0, 0, 0) } }
            wire.close()
        }
    }
}
