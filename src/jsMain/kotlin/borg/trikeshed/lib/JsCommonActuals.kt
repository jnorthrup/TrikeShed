package borg.trikeshed.lib

import borg.trikeshed.lib.long.LongSeries
import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JsFileOperations


actual object System {

    actual val homedir: CharSequence
        get() = jsHomeDir()

    actual fun getenv(name: CharSequence, defaultVal: CharSequence?): CharSequence? = (processObj.env[name] as? CharSequence) ?: defaultVal
}

fun readLinesSeq(path: CharSequence): Sequence<CharSequence> =
    Files.readAllLines(path).view.asSequence()

fun readLines(path: CharSequence): List<CharSequence> = Files.readAllLines(path).view.toList()
 data class JsHandleState(
    val fd: Int,
    var position: Long = 0,
)

class JsSeekHandle : SeekHandle {
   val handles = mutableMapOf<Long, JsHandleState>()
   var nextHandle = 1L

    override fun open(filename: CharSequence, readOnly: Boolean): Long {
        require(jsExists(filename)) { "File does not exist: $filename" }
        val fd = jsOpen(filename, readOnly)
        val handle = nextHandle++
        handles[handle] = JsHandleState(fd)
        return handle
    }

    override fun close(handle: Long) {
        handles.remove(handle)?.let { jsClose(it.fd) }
    }

    override fun pread(handle: Long, dst: ByteRegion, fileOffset: Long): Int {
        val state = handles[handle] ?: return -1
        val backing = dst.buffer.array()
        val offset = dst.buffer.arrayOffset() + dst.start
        return jsPread(state.fd, backing, offset, dst.size, fileOffset)
    }

    override fun pwrite(handle: Long, src: ByteSeries, fileOffset: Long): Int {
        val state = handles[handle] ?: return -1
        val bytes = src.toArray()
        return jsPwrite(state.fd, bytes, 0, bytes.size, fileOffset)
    }

    override fun size(handle: Long): Long {
        val state = handles[handle] ?: return -1
        // Get size by seeking to end
        val buf = ByteArray(1)
        var pos = 0L
        while (jsPread(state.fd, buf, 0, 1, pos) == 1) pos++
        return pos
    }

    override fun read(handle: Long, dst: ByteRegion): Int {
        val state = handles[handle] ?: return -1
        val backing = dst.buffer.array()
        val offset = dst.buffer.arrayOffset() + dst.start
        val count = jsPread(state.fd, backing, offset, dst.size, state.position)
        if (count > 0) state.position += count
        return count
    }

    override fun write(handle: Long, src: ByteSeries): Int {
        val state = handles[handle] ?: return -1
        val bytes = src.toArray()
        val count = jsPwrite(state.fd, bytes, 0, bytes.size, state.position)
        if (count > 0) state.position += count
        return count
    }

    override fun seek(handle: Long, position: Long): Long {
        val state = handles[handle] ?: return -1
        state.position = position.coerceAtLeast(0)
        return state.position
    }
}

actual fun platformSeekHandle(): SeekHandle = JsSeekHandle()

actual fun ioUringHandle(): SeekHandle? = null


class SeekFileBuffer(
    val filename: CharSequence,
    val initialOffset: Long = 0,
    val blkSize: Long = -1,
    val readOnly: Boolean = true,
) : LongSeries<Byte> {
   val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly)

    override val a: Long
        get() = delegate.a

    override val b: (Long) -> Byte
        get() = delegate.b

    fun close() {
        delegate.close()
    }

    fun open() {
        delegate.open()
    }

    fun isOpen(): Boolean = delegate.isOpen()

    fun size(): Long = delegate.size()

    fun get(index: Long): Byte = delegate.get(index)

    fun readv(requests: Series2<Long, ByteRegion>): IntArray = delegate.readv(requests)

    fun seek(pos: Long) {
        throw UnsupportedOperationException("seek not supported in JS")
    }

    fun put(index: Long, value: Byte) {
        throw UnsupportedOperationException("put not supported in JS")
    }
}
