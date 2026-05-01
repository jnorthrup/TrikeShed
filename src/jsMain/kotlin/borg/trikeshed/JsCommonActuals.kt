package borg.trikeshed

import borg.trikeshed.processObj
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries


actual object System {

    actual val homedir: String
        get() = jsHomeDir()

    actual fun getenv(name: String, defaultVal: String?): String? = (processObj.env[name] as? String) ?: defaultVal
}

actual object Files {
    actual fun readAllLines(filename: String): List<String> =
        jsReadString(filename).replace("\r\n", "\n").lines()

    actual fun readAllBytes(filename: String): ByteArray = jsReadBytes(filename)

    actual fun readString(filename: String): String = jsReadString(filename)

    actual fun write(filename: String, bytes: ByteArray) {
        jsWriteBytes(filename, bytes)
    }

    actual fun write(filename: String, lines: List<String>) {
        write(filename, lines.joinToString("\n"))
    }

    actual fun write(filename: String, string: String) {
        jsWriteString(filename, string)
    }

    actual fun cwd(): String = jsCwd()

    actual fun exists(filename: String): Boolean = jsExists(filename)

    actual fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        streamByteLines(readAllBytes(fileName))

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (offset, bytes) -> offset j bytes.toSeries() }.asIterable()
}
fun streamByteLines(bytes: ByteArray): Sequence<Join<Long, ByteArray>> = sequence {
    var offset = 0L
    var lineStart = 0L
    val line = ArrayList<Byte>()

    for (byte in bytes) {
        line += byte
        offset++
        if (byte == '\n'.code.toByte()) {
            yield(lineStart j line.toByteArray())
            line.clear()
            lineStart = offset
        }
    }

    if (line.isNotEmpty()) {
        yield(lineStart j line.toByteArray())
    }
}

actual fun mktemp(): String = jsMktemp()

actual fun rm(path: String): Boolean = jsRm(path)

actual fun mkdir(path: String): Boolean = jsMkdir(path)

actual fun readLinesSeq(path: String): Sequence<String> =
    Files.readAllLines(path).asSequence()

actual fun readLines(path: String): List<String> = Files.readAllLines(path)
data class JsHandleState(
    val fd: Int,
    var position: Long = 0,
)

class JsSeekHandle : SeekHandle {
   val handles = mutableMapOf<Long, JsHandleState>()
   var nextHandle = 1L

    override fun open(filename: String, readOnly: Boolean): Long {
        require(jsExists(filename)) { "File does not exist: $filename" }
        val fd = jsOpen(filename, readOnly)
        val handle = nextHandle++
        handles[handle] = JsHandleState(fd)
        return handle
    }

    override fun close(handle: Long) {
        handles.remove(handle)?.let { jsClose(it.fd) }
    }

    override fun pread(handle: Long, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
        val state = handles[handle] ?: return -1
        return jsPread(state.fd, buf, offset, length, fileOffset)
    }

    override fun pwrite(handle: Long, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
        val state = handles[handle] ?: return -1
        return jsPwrite(state.fd, buf, offset, length, fileOffset)
    }

    override fun size(handle: Long): Long {
        val state = handles[handle] ?: return -1
        // Get size by seeking to end
        val buf = ByteArray(1)
        var pos = 0L
        while (jsPread(state.fd, buf, 0, 1, pos) == 1) pos++
        return pos
    }

    override fun read(handle: Long, buf: ByteArray, offset: Int, length: Int): Int {
        val state = handles[handle] ?: return -1
        val count = jsPread(state.fd, buf, offset, length, state.position)
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

actual class FileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
    actual val closeChannelOnMap: Boolean,
) : LongSeries<Byte> {
   val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly)

    actual override val a: Long
        get() = delegate.a

    actual override val b: (Long) -> Byte
        get() = delegate.b

    actual fun close() {
        delegate.close()
    }

    actual fun open() {
        delegate.open()
    }

    actual fun isOpen(): Boolean = delegate.isOpen()

    actual fun size(): Long = delegate.size()

    actual fun get(index: Long): Byte = delegate.get(index)

    actual fun put(index: Long, value: Byte) {
        throw UnsupportedOperationException("put not supported in JS")
    }
}

actual class SeekFileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
) : LongSeries<Byte> {
   val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly)

    actual override val a: Long
        get() = delegate.a

    actual override val b: (Long) -> Byte
        get() = delegate.b

    actual fun close() {
        delegate.close()
    }

    actual fun open() {
        delegate.open()
    }

    actual fun isOpen(): Boolean = delegate.isOpen()

    actual fun size(): Long = delegate.size()

    actual fun get(index: Long): Byte = delegate.get(index)

    actual fun readv(requests: Series2<Long, ByteSeries>): IntArray = delegate.readv(requests)

    actual fun seek(pos: Long) {
        throw UnsupportedOperationException("seek not supported in JS")
    }

    actual fun put(index: Long, value: Byte) {
        throw UnsupportedOperationException("put not supported in JS")
    }
}
