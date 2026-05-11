package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.js.JsName

private object JsFileRegistry {
    private var nextId = 1
    private val contentCache = mutableMapOf<String, ByteArray>()

    fun open(path: String, readOnly: Boolean): FileImpl {
        val fi = FileImpl(nextId++)
        fi.path = path
        fi.knownSize = contentCache[path]?.size?.toLong() ?: -1L
        return fi
    }

    fun cache(path: String, data: ByteArray) {
        contentCache[path] = data
    }

    fun getContent(path: String): ByteArray? = contentCache[path]
}

private class JsUserspaceChannelBackend : UserspaceChannelBackend {
    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val content = JsFileRegistry.getContent(file.path) ?: return -1
        val start = offset.toInt().coerceIn(0, content.size)
        val end = (start + buffer.remaining()).coerceAtMost(content.size)
        val len = end - start
        if (len <= 0) return 0
        val dst = buffer.array()
        val dstStart = buffer.arrayOffset() + buffer.position()
        content.copyInto(dst, dstStart, start, end)
        buffer.position(buffer.position() + len)
        return len
    }

    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int = -1
    override fun accept(file: FileImpl): Int = -1
    override fun connect(file: FileImpl, address: String, port: Int): Int = -1
    override fun close(file: FileImpl): Int = 0
    override fun sync(file: FileImpl, metaData: Boolean): Int = 0
    override fun truncate(file: FileImpl, size: Long): Int = 0
    override fun map(file: FileImpl, mode: String, position: Long, size: Long): Int = -1
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = JsUserspaceChannelBackend()

actual class FileImpl actual constructor(actual val id: Int) {
    @PublishedApi internal var path: String = ""
    @PublishedApi internal var knownSize: Long = -1L
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {}
    actual fun size(): Long = knownSize
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl =
        JsFileRegistry.open(path, readOnly)
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(-1)
}

@PublishedApi
internal suspend fun loadFile(path: String) {
    val resp = jsFetch(path)
    val buf = resp.arrayBuffer()
    val int8Array = buf.unsafeCast<dynamic>()
    val len = int8Array.length as Int
    val arr = ByteArray(len) { int8Array[it].unsafeCast<Byte>() }
    JsFileRegistry.cache(path, arr)
}

@JsName("fetch")
external fun jsFetch(input: String): JsResponse

@JsName("Response")
external class JsResponse {
    val ok: Boolean
    val status: Int
    fun arrayBuffer(): dynamic
    fun text(): String
}