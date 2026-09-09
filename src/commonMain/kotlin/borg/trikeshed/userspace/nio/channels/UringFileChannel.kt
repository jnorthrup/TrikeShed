package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException

internal class UringFileChannel(
    private val file: File,
    private val channel: UringChannel,
    private val readable: Boolean = true,
    private val writable: Boolean = true,
) : FileChannel() {
    private var pos: Long = 0
    private var nextToken: Long = 1
    private var open: Boolean = true

    override fun implCloseChannel() = close()
    override fun begin() {}
    override fun end(completed: Boolean) {}

    private fun checkOpen() { if (!isOpen()) throw ClosedChannelException() }

    private fun execute(op: UringOp, buffer: ByteBuffer? = null, offset: Long = 0): Int {
        checkOpen()
        val token = nextToken++
        val start = buffer?.position() ?: 0
        val remaining = buffer?.remaining() ?: 0
        channel.enqueue(UringSubmission(op, file.id, 0, remaining, offset, userData = token, buffer = buffer))
        channel.submit()
        val completion = channel.wait(1).singleOrNull()
        if (completion == null || completion.userData != token) throw IOException("Invalid $op completion for token $token")
        val count = completion.res
        // This JVM adapter reports NIO EOF (-1); native uring reports zero.
        val eof = op == UringOp.READ && count == -1 && offset >= size()
        if (count < 0 && !eof) throw IOException("$op failed: $count")
        if (buffer != null) {
            if (count > remaining || buffer.position() != start + maxOf(0, count)) {
                throw IOException("Invalid $op transfer: count=$count remaining=$remaining position=${buffer.position()}")
            }
        } else if (count != 0) throw IOException("Invalid $op completion: $count")
        return count
    }

    override fun close() {
        if (!open) return
        var failure: Throwable? = null
        try { execute(UringOp.CLOSE) } catch (caught: Throwable) {
            failure = caught
            runCatching { file.close() }.exceptionOrNull()?.let { if (it !== caught) caught.addSuppressed(it) }
        } finally {
            open = false
            try { channel.closeNow() } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    override fun isOpen(): Boolean = open && file.isOpen()

    override fun read(dst: ByteBuffer): Int = read(dst, pos).also { if (it > 0) pos += it }

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        checkOpen()
        if (!readable) throw NonReadableChannelException()
        require(offset >= 0 && length >= 0 && offset <= dsts.size - length)
        var total: Long = 0
        for (i in offset until offset + length) {
            if (!dsts[i].hasRemaining()) continue
            val n = read(dsts[i])
            if (n < 0) return if (total == 0L) -1 else total
            total += n
            if (dsts[i].hasRemaining()) break
        }
        return total
    }

    override fun read(dsts: Array<out ByteBuffer>): Long = read(dsts, 0, dsts.size)

    override fun write(src: ByteBuffer): Int = write(src, pos).also { pos += it }

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        checkOpen()
        if (!writable) throw NonWritableChannelException()
        require(offset >= 0 && length >= 0 && offset <= srcs.size - length)
        var total: Long = 0
        for (i in offset until offset + length) {
            val n = write(srcs[i])
            total += n
            if (srcs[i].hasRemaining()) break
        }
        return total
    }

    override fun write(srcs: Array<out ByteBuffer>): Long = write(srcs, 0, srcs.size)

    override fun position(): Long { checkOpen(); return pos }
    override fun position(newPosition: Long): FileChannel { checkOpen(); require(newPosition >= 0); pos = newPosition; return this }
    override fun size(): Long { checkOpen(); return file.size().also { if (it < 0) throw IOException("File size unavailable") } }

    override fun truncate(size: Long): FileChannel {
        checkOpen()
        if (!writable) throw NonWritableChannelException()
        require(size >= 0)
        if (size < size()) execute(UringOp.FTRUNCATE, offset = size)
        if (pos > size) pos = size
        return this
    }

    override fun force(metaData: Boolean) {
        execute(UringOp.FSYNC)
    }

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long {
        checkOpen()
        if (!readable) throw NonReadableChannelException()
        require(position >= 0 && count >= 0 && position <= Long.MAX_VALUE - count)
        val buf = ByteBuffer.allocate(minOf(count, 65536L).toInt())
        var total = 0L
        while (total < count) {
            buf.clear()
            buf.limit(minOf(buf.capacity().toLong(), count - total).toInt())
            val n = read(buf, position + total)
            if (n <= 0) break
            buf.flip()
            while (buf.hasRemaining()) {
                val start = buf.position()
                val remaining = buf.remaining()
                val written = target.write(buf)
                if (written < 0 || written > remaining || buf.position() != start + written) throw IOException("Invalid transfer write")
                if (written == 0) return total
                total += written
            }
        }
        return total
    }

    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long {
        checkOpen()
        if (!writable) throw NonWritableChannelException()
        require(position >= 0 && count >= 0 && position <= Long.MAX_VALUE - count)
        if (position > size()) return 0
        var total = 0L
        val buf = ByteBuffer.allocate(minOf(count, 65536L).toInt())
        while (total < count) {
            buf.clear()
            buf.limit(minOf(buf.capacity().toLong(), count - total).toInt())
            val n = src.read(buf)
            if (n < -1 || n > buf.limit() || buf.position() != maxOf(0, n)) throw IOException("Invalid transfer read")
            if (n <= 0) break
            buf.flip()
            while (buf.hasRemaining()) {
                val written = write(buf, position + total)
                if (written == 0) return total
                total += written
            }
        }
        return total
    }

    override fun read(dst: ByteBuffer, position: Long): Int {
        checkOpen()
        if (!readable) throw NonReadableChannelException()
        require(position >= 0 && position <= Long.MAX_VALUE - dst.remaining())
        if (!dst.hasRemaining()) return 0
        val count = execute(UringOp.READ, dst, position)
        return if (count == 0) -1 else count
    }

    override fun write(src: ByteBuffer, position: Long): Int {
        checkOpen()
        if (!writable) throw NonWritableChannelException()
        require(position >= 0 && position <= Long.MAX_VALUE - src.remaining())
        if (!src.hasRemaining()) return 0
        return execute(UringOp.WRITE, src, position)
    }

    override fun map(mode: MapMode, position: Long, size: Long): ByteBuffer {
        channel.map(file, mode.toString(), position, size, nextToken++)
        channel.submit()
        channel.wait(1)
        val buf = ByteBuffer.allocateDirect(size.toInt())
        val saved = pos
        pos = position
        read(buf)
        pos = saved
        buf.flip()
        return buf
    }

    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = object : FileLock(this, position, size, shared) {
        override fun isValid(): Boolean = acquiredBy().isOpen()
        override fun release() {}
    }
    override fun lock(): FileLock = lock(0, size(), true)
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = null
    override fun tryLock(): FileLock? = null
}
