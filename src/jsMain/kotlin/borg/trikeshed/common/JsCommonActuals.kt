package borg.trikeshed.common

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.toSeries

actual object System {
    actual fun getenv(name: String, string: String): String? = (js("process").env[name] as? String)

    actual val homedir: String
        get() = jsHomeDir()
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
        TODO("JS streamLines is stubbed; use readAllLines/readString instead")

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        TODO("JS iterateLines is stubbed; use readAllLines/readString instead")
}

actual fun mktemp(): String = jsMktemp()

actual fun rm(path: String): Boolean = jsRm(path)

actual fun mkdir(path: String): Boolean = jsMkdir(path)

actual fun readLinesSeq(path: String): Sequence<String> =
    Files.readAllLines(path).asSequence()

actual fun readLines(path: String): List<String> = Files.readAllLines(path)

private data class JsHandleState(
    val filename: String,
    var position: Long = 0,
)

class JsSeekHandle : SeekHandle {
    private val handles = mutableMapOf<Long, JsHandleState>()
    private var nextHandle = 1L

    override fun open(filename: String, readOnly: Boolean): Long {
        val handle = nextHandle++
        handles[handle] = JsHandleState(filename)
        return handle
    }

    override fun close(handle: Long) {
        handles.remove(handle)
    }

    override fun pread(handle: Long, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
        val state = handles[handle] ?: return -1
        val bytes = if (Files.exists(state.filename)) Files.readAllBytes(state.filename) else ByteArray(0)
        val start = fileOffset.toInt().coerceAtLeast(0)
        if (start >= bytes.size) return -1
        val count = minOf(length, bytes.size - start)
        for (i in 0 until count) {
            buf[offset + i] = bytes[start + i]
        }
        return count
    }

    override fun size(handle: Long): Long {
        val state = handles[handle] ?: return -1
        return if (Files.exists(state.filename)) Files.readAllBytes(state.filename).size.toLong() else 0L
    }

    override fun read(handle: Long, buf: ByteArray, offset: Int, length: Int): Int {
        val state = handles[handle] ?: return -1
        val bytes = if (Files.exists(state.filename)) Files.readAllBytes(state.filename) else ByteArray(0)
        val start = state.position.toInt().coerceAtLeast(0)
        if (start >= bytes.size) return -1
        val count = minOf(length, bytes.size - start)
        for (i in 0 until count) {
            buf[offset + i] = bytes[start + i]
        }
        state.position += count.toLong()
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
    private val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly)

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
        val bytes = if (Files.exists(filename)) Files.readAllBytes(filename) else ByteArray(0)
        val absolute = (initialOffset + index).toInt()
        val updated = if (absolute < bytes.size) {
            bytes[absolute] = value
            bytes
        } else {
            ByteArray(absolute + 1).also { expanded ->
                bytes.copyInto(expanded)
                expanded[absolute] = value
            }
        }
        Files.write(filename, updated)
    }
}

actual class SeekFileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
) : LongSeries<Byte> {
    private val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly)

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

    actual fun seek(pos: Long) {
        delegate.seek(pos)
    }

    actual fun put(index: Long, value: Byte) {
        val bytes = if (Files.exists(filename)) Files.readAllBytes(filename) else ByteArray(0)
        val absolute = (initialOffset + index).toInt()
        val updated = if (absolute < bytes.size) {
            bytes[absolute] = value
            bytes
        } else {
            ByteArray(absolute + 1).also { expanded ->
                bytes.copyInto(expanded)
                expanded[absolute] = value
            }
        }
        Files.write(filename, updated)
    }

    actual fun readv(requests: Series2<Long, ByteSeries>): IntArray = delegate.readv(requests)
}
