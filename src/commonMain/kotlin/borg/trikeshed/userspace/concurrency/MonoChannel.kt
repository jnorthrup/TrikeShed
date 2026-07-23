package borg.trikeshed.userspace.concurrency

import borg.trikeshed.userspace.nio.channels.ReadableByteChannel
import borg.trikeshed.userspace.nio.channels.WritableByteChannel
import borg.trikeshed.userspace.nio.ByteBuffer

class MonoChannel(
    private val stdin: ReadableByteChannel,
    private val stdout: WritableByteChannel
) {
    // Current frame state
    private var pendingLength: Long? = null
    private val lengthBuffer = ByteBuffer.allocate(8)
    private var msgBuffer: ByteBuffer? = null

    fun writeMessage(message: ByteArray) {
        val buf = ByteBuffer.allocate(8 + message.size)
        buf.putLong(message.size.toLong())
        buf.put(message)
        buf.flip()
        while (buf.hasRemaining()) {
            stdout.write(buf)
        }
    }

    fun readMessage(): ByteArray? {
        if (pendingLength == null) {
            val read = stdin.read(lengthBuffer)
            if (!lengthBuffer.hasRemaining()) {
                lengthBuffer.flip()
                pendingLength = lengthBuffer.getLong()
                lengthBuffer.clear()
            } else {
                return null
            }
        }

        val length = pendingLength!!
        if (msgBuffer == null) {
            msgBuffer = ByteBuffer.allocate(length.toInt())
        }

        val buf = msgBuffer!!
        val read = stdin.read(buf)

        if (!buf.hasRemaining()) {
            buf.flip()
            val bytes = ByteArray(length.toInt())
            buf.get(bytes)

            pendingLength = null
            msgBuffer = null

            return bytes
        }
        return null
    }
}
