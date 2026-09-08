package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException

internal class UringFileChannel(private val file: File, private val channel: UringChannel) : FileChannel() {
    private var pos = 0L
    private var nextToken = 1L
    private var open = true
    override fun implCloseChannel() = close()
    override fun begin() {}
    override fun end(completed: Boolean) {}
    override fun isOpen(): Boolean = open && file.isOpen()
    private fun checkOpen() { check(isOpen()) { "File channel is closed" } }

    private fun execute(op: UringOp, buffer: ByteBuffer? = null, offset: Long = 0): Int {
        checkOpen()
        val token = nextToken++
        channel.enqueue(UringSubmission(op, file.id, 0, buffer?.remaining() ?: 0, offset,
            userData = token, buffer = buffer))
        channel.submit()
        val cqe = channel.wait(1).single { it.userData == token }
        if (cqe.res < 0) throw IOException("$op errno ${-cqe.res}")
        return cqe.res
    }
    override fun close() {
        if (!open) return
        try { execute(UringOp.CLOSE) } finally { open = false; channel.closeNow() }
    }
    override fun read(dst: ByteBuffer): Int = read(dst, pos).also { if (it > 0) pos += it }
    override fun read(dst: ByteBuffer, position: Long): Int {
        require(position >= 0)
        if (!dst.hasRemaining()) { checkOpen(); return 0 }
        val count = execute(UringOp.READ, dst, position)
        return if (count == 0) -1 else count // NIO EOF; the shared CQE keeps uring's zero.
    }
    override fun write(src: ByteBuffer): Int = write(src, pos).also { pos += it }
    override fun write(src: ByteBuffer, position: Long): Int {
        require(position >= 0)
        return execute(UringOp.WRITE, src, position)
    }
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        require(offset >= 0 && length >= 0 && offset <= dsts.size - length)
        var total = 0L
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
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        require(offset >= 0 && length >= 0 && offset <= srcs.size - length)
        var total = 0L
        for (i in offset until offset + length) {
            total += write(srcs[i])
            if (srcs[i].hasRemaining()) break
        }
        return total
    }
    override fun write(srcs: Array<out ByteBuffer>): Long = write(srcs, 0, srcs.size)
    override fun position(): Long { checkOpen(); return pos }
    override fun position(newPosition: Long): FileChannel { checkOpen(); require(newPosition >= 0); pos = newPosition; return this }
    override fun size(): Long { checkOpen(); return file.size().also { if (it < 0) throw IOException("File size unavailable") } }
    override fun truncate(size: Long): FileChannel {
        require(size >= 0)
        if (size < size()) execute(UringOp.FTRUNCATE, offset = size)
        if (pos > size) pos = size
        return this
    }
    override fun force(metaData: Boolean) { execute(UringOp.FSYNC) }
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long {
        require(position >= 0 && count >= 0)
        val buffer = ByteBuffer.allocate(minOf(count, 65536L).toInt())
        var total = 0L
        while (total < count) {
            buffer.clear().limit(minOf(buffer.capacity().toLong(), count - total).toInt())
            if (read(buffer, position + total) <= 0) break
            buffer.flip()
            while (buffer.hasRemaining()) {
                val n = target.write(buffer)
                if (n == 0) return total
                if (n < 0) throw IOException("Negative transfer write result")
                total += n
            }
        }
        return total
    }
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long {
        require(position >= 0 && count >= 0)
        if (position > size()) return 0
        val buffer = ByteBuffer.allocate(minOf(count, 65536L).toInt())
        var total = 0L
        while (total < count) {
            buffer.clear().limit(minOf(buffer.capacity().toLong(), count - total).toInt())
            if (src.read(buffer) <= 0) break
            buffer.flip()
            while (buffer.hasRemaining()) {
                val n = write(buffer, position + total)
                if (n == 0) return total
                total += n
            }
        }
        return total
    }
    override fun map(mode: MapMode, position: Long, size: Long): ByteBuffer =
        throw UnsupportedOperationException("Memory mapping is not an SQE operation")
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock =
        throw UnsupportedOperationException("File locking is not implemented")
    override fun lock(): FileLock = lock(0, Long.MAX_VALUE, false)
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? =
        throw UnsupportedOperationException("File locking is not implemented")
    override fun tryLock(): FileLock? = tryLock(0, Long.MAX_VALUE, false)
}
