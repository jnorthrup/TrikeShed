package borg.trikeshed.couch.isam

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.io.File

/**
 * JVM specific file-backed implementation of DurableAppendLog.
 */
class JvmDurableAppendLog(private val file: File) : DurableAppendLog {
    private val raf = RandomAccessFile(file, "rw")
    private val channel: FileChannel = raf.channel

    override fun append(sequence: Long, payload: ByteArray): Long {
        val frame = WalFrame.encode(sequence, payload)
        val buf = ByteBuffer.wrap(frame)
        channel.position(channel.size())
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
        return sequence
    }

    override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
        channel.position(0)
        var lastValidSequence = 0L

        while (true) {
            val startPos = channel.position()
            // Try to read header to know frame size
            val headerBuf = ByteBuffer.allocate(WalFrame.HEADER_SIZE)
            var read = 0
            while (headerBuf.hasRemaining()) {
                val r = channel.read(headerBuf)
                if (r == -1) break
                read += r
            }
            if (read < WalFrame.HEADER_SIZE) {
                // Incomplete header, stop replay
                break
            }

            headerBuf.flip()

            // Magic
            val magic = ByteArray(4)
            headerBuf.get(magic)
            var validMagic = true
            for (i in 0..3) {
                if (magic[i] != WalFrame.MAGIC[i]) validMagic = false
            }
            if (!validMagic) break

            // Version
            val version = headerBuf.short

            // Sequence
            val sequence = headerBuf.long

            // Payload Length
            val payloadLen = headerBuf.int

            if (payloadLen < 0 || payloadLen > 10 * 1024 * 1024) {
                // Sanity check, prevent out of memory on corrupt length
                // Bounded to 10MB
                break
            }

            // Read rest of frame (payload + crc)
            val restBuf = ByteBuffer.allocate(payloadLen + 4)
            var restRead = 0
            while (restBuf.hasRemaining()) {
                val r = channel.read(restBuf)
                if (r == -1) break
                restRead += r
            }

            if (restRead < payloadLen + 4) {
                // Incomplete frame, stop replay
                break
            }

            // Validate full frame
            val frameSize = WalFrame.HEADER_SIZE + payloadLen + 4
            val fullFrame = ByteArray(frameSize)

            channel.position(startPos)
            val fullBuf = ByteBuffer.wrap(fullFrame)
            while (fullBuf.hasRemaining()) {
                channel.read(fullBuf)
            }

            if (WalFrame.validate(fullFrame)) {
                // Frame is valid, extract payload and invoke callback
                val payload = fullFrame.sliceArray(WalFrame.HEADER_SIZE until WalFrame.HEADER_SIZE + payloadLen)
                onFrame(sequence, payload)
                lastValidSequence = sequence
            } else {
                // CRC failed, stop replay
                break
            }
        }

        return lastValidSequence
    }

    override fun flush() {
        channel.force(true)
    }

    override fun injectCorruptionAfter(sequence: Long) {
        // Find the end of the specified sequence and truncate to cause a torn frame, or write garbage.
        channel.position(0)
        while (true) {
            val startPos = channel.position()
            val headerBuf = ByteBuffer.allocate(WalFrame.HEADER_SIZE)
            var read = 0
            while (headerBuf.hasRemaining()) {
                val r = channel.read(headerBuf)
                if (r == -1) break
                read += r
            }
            if (read < WalFrame.HEADER_SIZE) break

            headerBuf.flip()
            headerBuf.position(6) // skip magic and version
            val currentSeq = headerBuf.long
            val payloadLen = headerBuf.int

            val restLen = payloadLen + 4
            channel.position(channel.position() + restLen)

            if (currentSeq == sequence) {
                // Inject corruption right here.
                // Write half a header to simulate torn frame
                val badData = ByteBuffer.wrap(byteArrayOf(0, 1, 2, 3))
                channel.write(badData)
                channel.truncate(channel.position())
                break
            }
        }
    }
}
