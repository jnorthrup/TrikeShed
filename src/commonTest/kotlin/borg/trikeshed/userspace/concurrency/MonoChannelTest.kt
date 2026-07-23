package borg.trikeshed.userspace.concurrency

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.ReadableByteChannel
import borg.trikeshed.userspace.nio.channels.WritableByteChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MockReadableByteChannel(private val buf: ByteBuffer) : ReadableByteChannel {
    override fun read(dst: ByteBuffer): Int {
        val rem = dst.remaining()
        if (rem == 0) return 0
        if (!buf.hasRemaining()) return 0
        val toRead = minOf(rem, buf.remaining())
        val oldLimit = buf.limit()
        buf.limit(buf.position() + toRead)
        dst.put(buf)
        buf.limit(oldLimit)
        return toRead
    }
    override fun close() {}
    override fun isOpen() = true
}

class MockWritableByteChannel(val buf: ByteBuffer) : WritableByteChannel {
    override fun write(src: ByteBuffer): Int {
        val rem = src.remaining()
        buf.put(src)
        return rem
    }
    override fun close() {}
    override fun isOpen() = true
}

class MonoChannelTest {
    @Test
    fun testMonoChannelFraming() {
        val stdinBuf = ByteBuffer.allocate(1024)
        val stdoutBuf = ByteBuffer.allocate(1024)

        val stdin = MockReadableByteChannel(stdinBuf)
        val stdout = MockWritableByteChannel(stdoutBuf)

        val channel = MonoChannel(stdin, stdout)

        // Write message via channel
        val msg1 = "Hello".encodeToByteArray()
        channel.writeMessage(msg1)

        // Check stdout framing
        stdoutBuf.flip()
        assertEquals(5L, stdoutBuf.getLong())
        val outMsg = ByteArray(5)
        stdoutBuf.get(outMsg)
        assertEquals("Hello", outMsg.decodeToString())

        // Feed stdin with incomplete frame
        stdinBuf.putLong(5L)
        stdinBuf.put(byteArrayOf(72, 101, 108)) // "Hel"
        stdinBuf.flip()

        // Should be non-blocking and return null
        assertNull(channel.readMessage())

        // Complete the frame
        val pos = stdinBuf.position()
        stdinBuf.position(stdinBuf.limit())
        stdinBuf.limit(stdinBuf.capacityInt)
        stdinBuf.put(byteArrayOf(108, 111)) // "lo"
        stdinBuf.limit(stdinBuf.position())
        stdinBuf.position(pos)

        // Now it should return the message
        val readMsg = channel.readMessage()
        assertEquals("Hello", readMsg?.decodeToString())

        // Enum check
        assertEquals("MonoChannel", ChannelDefinition.Enum.MonoChannel.name)
    }
}
