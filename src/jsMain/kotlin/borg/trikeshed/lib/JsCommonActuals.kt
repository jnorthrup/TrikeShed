package borg.trikeshed.lib

import borg.trikeshed.lib.long.LongSeries
import borg.trikeshed.userspace.ByteRegion
import kotlin.random.Random
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Files
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.SeekFileBufferCommon
import borg.trikeshed.lib.SeekHandle
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

    actual fun listDir(path: String): List<String> {
        val entries: dynamic = fs.readdirSync(path)
        val result = mutableListOf<String>()
        val length = entries.length as Int
        for (i in 0 until length) result.add(entries[i] as String)
        return result
    }

    actual fun isDir(path: String): Boolean {
        val stat: dynamic = fs.statSync(path)
        return (stat.isDirectory() as Boolean)
    }

    actual fun isFile(path: String): Boolean {
        val stat: dynamic = fs.statSync(path)
        return (stat.isFile() as Boolean)
    }

    actual fun mkdirs(path: String) { jsMkdir(path) }

    actual fun deleteRecursively(path: String) { jsRm(path) }

    actual fun resolvePath(vararg parts: String): String =
        path.join(jsCwd(), parts.joinToString("/")) as String

    actual fun readZip(path: String): List<Pair<String, ByteArray>> = TODO("readZip JS")

    actual fun createTempDir(prefix: String): String {
        val dir = path.join(os.tmpdir(), "$prefix-${Random.nextInt(1_000_000)}") as String
        jsMkdir(dir)
        return dir
    }
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

fun mktemp(): String = jsMktemp()

fun rm(path: String): Boolean = jsRm(path)

fun mkdir(path: String): Boolean = jsMkdir(path)

fun readLinesSeq(path: String): Sequence<String> =
    Files.readAllLines(path).asSequence()

fun readLines(path: String): List<String> = Files.readAllLines(path)
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
    val filename: String,
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
