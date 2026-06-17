package borg.trikeshed.tcpd

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * TCP session for a single connection.
 * Routes HTX frames (magic + length + payload) on the wire.
 */
class TcpSession(
    val sessionId: String,
    private val socket: SocketChannel,
    private val config: TcpDaemonConfig,
    private val daemon: TcpDaemonElement,
) {
    private var seq: Int = 0
    private val readBuffer = ByteBuffer.allocate(config.maxFrameSize)

    /** Frame handler - override to process frames. */
    suspend fun handleFrame(frame: TcpFrame): TcpFrame? {
        // Default: echo back
        return frame
    }

    suspend fun run() {
        println("[TcpSession:$sessionId] Started")
        
        try {
            while (socket.isOpen) {
                val frame = readFrame() ?: break
                val response = handleFrame(frame)
                if (response != null) {
                    writeFrame(response)
                }
            }
        } catch (e: Exception) {
            println("[TcpSession:$sessionId] Error: ${e.message}")
        } finally {
            close()
            println("[TcpSession:$sessionId] Closed")
        }
    }

    private fun readFrame(): TcpFrame? {
        readBuffer.clear()
        val header = ByteBuffer.allocate(8)
        while (header.hasRemaining()) {
            val read = socket.read(header) ?: return null
            if (read == -1) return null
        }
        
        val magic = header.getInt(0)
        if (magic != HTX_MAGIC) {
            println("[TcpSession:$sessionId] Invalid magic: ${Integer.toHexString(magic)}")
            return null
        }
        
        val length = header.getInt(4)
        if (length > config.maxFrameSize || length <= 0) {
            println("[TcpSession:$sessionId] Invalid length: $length")
            return null
        }
        
        readBuffer.limit(length)
        while (readBuffer.hasRemaining()) {
            val read = socket.read(readBuffer) ?: return null
            if (read == -1) return null
        }
        
        readBuffer.flip()
        return parseFrame(readBuffer)
    }

    private fun parseFrame(buf: ByteBuffer): TcpFrame {
        val protocol = buf.get().toInt()
        val peer = readString(buf)
        val method = readString(buf)
        val payload = readString(buf)
        
        return TcpFrame(
            protocol = protocol,
            peer = peer,
            method = method,
            payload = payload,
            seq = seq++,
        )
    }

    private fun writeFrame(frame: TcpFrame): Boolean {
        val buf = ByteBuffer.allocate(8 + estimateSize(frame))
        buf.putInt(HTX_MAGIC)
        buf.putInt(buf.capacity() - 8)
        buf.put(frame.protocol.toByte())
        writeString(buf, frame.peer)
        writeString(buf, frame.method)
        writeString(buf, frame.payload)
        buf.flip()
        
        while (buf.hasRemaining()) {
            socket.write(buf)
        }
        
        return true
    }

    fun close() = socket.close()

    companion object {
        const val HTX_MAGIC = 0x48545801
        
        private fun readString(buf: ByteBuffer): String {
            val len = buf.int
            val bytes = ByteArray(len)
            buf.get(bytes)
            return String(bytes)
        }
        
        private fun writeString(buf: ByteBuffer, s: String) {
            val bytes = s.toByteArray()
            buf.putInt(bytes.size)
            buf.put(bytes)
        }
        
        private fun estimateSize(frame: TcpFrame): Int {
            return 1 + 4 + frame.peer.length + 4 + frame.method.length + 4 + frame.payload.length
        }
    }
}

/** HTX frame. */
data class TcpFrame(
    val protocol: Int,
    val peer: String,
    val method: String,
    val payload: String,
    val seq: Int,
)