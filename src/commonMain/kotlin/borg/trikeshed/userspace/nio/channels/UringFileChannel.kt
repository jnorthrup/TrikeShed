package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.Channel
import borg.trikeshed.userspace.File
import borg.trikeshed.userspace.nio.ByteBuffer

internal class UringFileChannel(
    private val file: File,
    private val channel: Channel,
) : FileChannel() {
    private var pos: Long = 0
    private var nextToken: Long = 1
    private var open: Boolean = true

    override fun implCloseChannel() {}
    override fun begin() {}
    override fun end(completed: Boolean) {}

    override fun close() {
        if (!open) return
        channel.close(file, nextToken++)
        channel.submit()
        open = false
    }

    override fun isOpen(): Boolean = open

    override fun read(dst: ByteBuffer): Int {
        val token = nextToken++
        channel.read(file, dst, pos, token)
        channel.submit()
        val completed = channel.wait(1)
        val bytesRead = completed.firstOrNull()?.res ?: -1
        if (bytesRead > 0) pos += bytesRead
        return bytesRead
    }

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        var total: Long = 0
        for (i in offset until (offset + length).coerceAtMost(dsts.size)) {
            val n = read(dsts[i])
            if (n < 0) return if (total == 0L) -1 else total
            total += n
        }
        return total
    }

    override fun read(dsts: Array<out ByteBuffer>): Long = read(dsts, 0, dsts.size)

    override fun write(src: ByteBuffer): Int {
        val token = nextToken++
        channel.write(file, src, pos, token)
        channel.submit()
        val completed = channel.wait(1)
        val bytesWritten = completed.firstOrNull()?.res ?: -1
        if (bytesWritten > 0) pos += bytesWritten
        return bytesWritten
    }

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        var total: Long = 0
        for (i in offset until (offset + length).coerceAtMost(srcs.size)) {
            val n = write(srcs[i])
            if (n < 0) return if (total == 0L) -1 else total
            total += n
        }
        return total
    }

    override fun write(srcs: Array<out ByteBuffer>): Long = write(srcs, 0, srcs.size)

    override fun position(): Long = pos
    override fun position(newPosition: Long): FileChannel { pos = newPosition; return this }
    override fun size(): Long = file.size()

    override fun truncate(size: Long): FileChannel {
        if (size < file.size()) {
            channel.truncate(file, size, nextToken++)
            channel.submit()
            channel.wait(1)
        }
        return this
    }

    override fun force(metaData: Boolean) {
        channel.sync(file, nextToken++, metaData)
        channel.submit()
        channel.wait(1)
    }

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long {
        val saved = pos
        pos = position
        val buf = ByteBuffer.allocate(count.coerceAtMost(65536).toInt())
        var total = 0L
        var remaining = count
        while (remaining > 0) {
            buf.clear()
            buf.limit(buf.capacity().coerceAtMost(remaining.toInt()))
            val n = read(buf)
            if (n <= 0) break
            buf.flip()
            val written = target.write(buf)
            total += written
            remaining -= written
        }
        pos = saved
        return total
    }

    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long {
        val saved = pos
        pos = position
        var total = 0L
        var remaining = count
        val buf = ByteBuffer.allocate(65536)
        while (remaining > 0) {
            buf.clear()
            buf.limit(buf.capacity().coerceAtMost(remaining.toInt()))
            val n = src.read(buf)
            if (n <= 0) break
            buf.flip()
            val written = write(buf)
            total += written
            remaining -= written
        }
        pos = saved
        return total
    }

    override fun read(dst: ByteBuffer, position: Long): Int {
        val saved = pos
        pos = position
        val n = read(dst)
        pos = saved
        return n
    }

    override fun write(src: ByteBuffer, position: Long): Int {
        val saved = pos
        pos = position
        val n = write(src)
        pos = saved
        return n
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

    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = FileLock(this, position, size, shared)
    override fun lock(): FileLock = lock(0, size(), true)
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = null
    override fun tryLock(): FileLock? = null
}