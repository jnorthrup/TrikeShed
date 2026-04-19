package borg.trikeshed.common

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlin.random.Random

@JsName("localStorage")
private external val browserLocalStorage: Storage?

private external class Storage {
    val length: Int
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
    fun removeItem(key: String)
    fun key(index: Int): String?
}

private const val FILE_PREFIX = "trikeshed:browser:file:"
private const val DIR_PREFIX = "trikeshed:browser:dir:"

private val blobFallback = linkedMapOf<String, String>()
private val dirFallback = linkedSetOf<String>()
private val envFallback = linkedMapOf<String, String>()

private fun storageOrNull(): Storage? =
    try {
        browserLocalStorage
    } catch (_: Throwable) {
        null
    }

private fun normalizePath(path: String): String {
    val normalized = path.replace('\\', '/')
    val parts = mutableListOf<String>()
    for (part in normalized.split('/')) {
        when {
            part.isEmpty() || part == "." -> {}
            part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return "/" + parts.joinToString("/")
}

private fun parentPath(path: String): String? {
    val normalized = normalizePath(path)
    val cut = normalized.lastIndexOf('/')
    if (cut <= 0) return "/"
    return normalized.substring(0, cut)
}

private fun fileKey(path: String): String = FILE_PREFIX + normalizePath(path)

private fun dirKey(path: String): String = DIR_PREFIX + normalizePath(path)

private fun storageGet(key: String): String? =
    storageOrNull()?.let {
        try {
            it.getItem(key)
        } catch (_: Throwable) {
            null
        }
    }

private fun storageSet(key: String, value: String): Boolean {
    val storage = storageOrNull() ?: return false
    return try {
        storage.setItem(key, value)
        true
    } catch (_: Throwable) {
        false
    }
}

private fun storageRemove(key: String): Boolean {
    val storage = storageOrNull() ?: return false
    return try {
        storage.removeItem(key)
        true
    } catch (_: Throwable) {
        false
    }
}

private fun storageKeys(prefix: String): List<String> {
    val storage = storageOrNull() ?: return emptyList()
    val keys = mutableListOf<String>()
    for (index in 0 until storage.length) {
        val key = try {
            storage.key(index)
        } catch (_: Throwable) {
            null
        }
        if (key != null && key.startsWith(prefix)) keys += key
    }
    return keys
}

private fun readBlob(path: String): String? {
    val key = fileKey(path)
    return storageGet(key) ?: blobFallback[key]
}

private fun writeBlob(path: String, hex: String) {
    val key = fileKey(path)
    if (!storageSet(key, hex)) {
        blobFallback[key] = hex
    } else {
        blobFallback.remove(key)
    }
}

private fun removeBlob(path: String): Boolean {
    val key = fileKey(path)
    val removedStorage = storageRemove(key)
    val removedFallback = blobFallback.remove(key) != null
    return removedStorage || removedFallback
}

private fun markDirectory(path: String) {
    val normalized = normalizePath(path)
    if (!storageSet(dirKey(normalized), "1")) {
        dirFallback += normalized
    } else {
        dirFallback.remove(normalized)
    }
}

private fun unmarkDirectory(path: String): Boolean {
    val normalized = normalizePath(path)
    val removedStorage = storageRemove(dirKey(normalized))
    val removedFallback = dirFallback.remove(normalized)
    return removedStorage || removedFallback
}

private fun directoryExists(path: String): Boolean {
    val normalized = normalizePath(path)
    return storageGet(dirKey(normalized)) != null || normalized in dirFallback
}

private fun ensureParentDirectories(path: String) {
    var current = parentPath(path)
    while (current != null && current != "/") {
        markDirectory(current)
        current = parentPath(current)
    }
    markDirectory("/")
}

private fun encodeHex(bytes: ByteArray): String {
    val chars = CharArray(bytes.size * 2)
    val digits = "0123456789abcdef"
    var out = 0
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        chars[out++] = digits[value ushr 4]
        chars[out++] = digits[value and 0x0F]
    }
    return chars.concatToString()
}

private fun decodeHex(value: String): ByteArray {
    if (value.isEmpty()) return ByteArray(0)
    val size = value.length / 2
    return ByteArray(size) { index ->
        val hi = value[index * 2].digitToInt(16)
        val lo = value[index * 2 + 1].digitToInt(16)
        ((hi shl 4) or lo).toByte()
    }
}

private fun streamByteLines(bytes: ByteArray): Sequence<Join<Long, ByteArray>> = sequence {
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

private object WasmBrowserSeekHandle : SeekHandle {
    private data class HandleState(
        val filename: String,
        var position: Long = 0,
    )

    private val handles = mutableMapOf<Long, HandleState>()
    private var nextHandle = 1L

    override fun open(filename: String, readOnly: Boolean): Long {
        if (!Files.exists(filename)) {
            throw IllegalArgumentException("File does not exist: $filename")
        }
        val handle = nextHandle++
        handles[handle] = HandleState(filename = normalizePath(filename))
        return handle
    }

    override fun close(handle: Long) {
        handles.remove(handle)
    }

    override fun pread(handle: Long, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
        val state = handles[handle] ?: return -1
        val bytes = Files.readAllBytes(state.filename)
        val start = fileOffset.toInt().coerceAtLeast(0)
        if (start >= bytes.size) return -1
        val count = minOf(length, bytes.size - start)
        for (index in 0 until count) {
            buf[offset + index] = bytes[start + index]
        }
        return count
    }

    override fun size(handle: Long): Long {
        val state = handles[handle] ?: return -1
        return Files.readAllBytes(state.filename).size.toLong()
    }

    override fun read(handle: Long, buf: ByteArray, offset: Int, length: Int): Int {
        val state = handles[handle] ?: return -1
        val bytesRead = pread(handle, buf, offset, length, state.position)
        if (bytesRead > 0) state.position += bytesRead.toLong()
        return bytesRead
    }

    override fun seek(handle: Long, position: Long): Long {
        val state = handles[handle] ?: return -1
        state.position = position.coerceAtLeast(0)
        return state.position
    }
}

actual object System {
    actual fun getenv(name: String, defaultVal: String?): String? {
        return envFallback[name] ?: defaultVal
    }

    fun setEnv(name: String, value: String?) {
        if (value == null) envFallback.remove(name) else envFallback[name] = value
    }

    fun setEnvMap(map: Map<String, String>?) {
        envFallback.clear()
        if (map != null) envFallback.putAll(map)
    }

    fun clearEnv() {
        envFallback.clear()
    }

    actual val homedir: String
        get() = getenv("HOME", getenv("USERPROFILE", "/")) ?: "/"
}

actual object Files {
    actual fun readAllLines(filename: String): List<String> =
        readString(filename).replace("\r\n", "\n").split('\n').let { parts ->
            if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        }

    actual fun readAllBytes(filename: String): ByteArray =
        readBlob(filename)?.let(::decodeHex) ?: ByteArray(0)

    actual fun readString(filename: String): String = readAllBytes(filename).decodeToString()

    actual fun write(filename: String, bytes: ByteArray) {
        ensureParentDirectories(filename)
        writeBlob(filename, encodeHex(bytes))
    }

    actual fun write(filename: String, lines: List<String>) {
        write(filename, lines.joinToString("\n"))
    }

    actual fun write(filename: String, string: String) {
        write(filename, string.encodeToByteArray())
    }

    actual fun cwd(): String = "/"

    actual fun exists(filename: String): Boolean =
        readBlob(filename) != null || directoryExists(filename)

    actual fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        streamByteLines(readAllBytes(fileName))

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (offset, bytes) -> offset j bytes.toSeries() }.asIterable()
}

actual fun mktemp(): String {
    val name = "/tmp/wasm-${Random.nextLong().toString(16)}.tmp"
    Files.write(name, ByteArray(0))
    return name
}

actual fun rm(path: String): Boolean {
    val normalized = normalizePath(path)
    val fileRemoved = removeBlob(normalized)

    val nestedFilePrefix = fileKey(normalized).trimEnd('/') + "/"
    val nestedDirPrefix = dirKey(normalized).trimEnd('/') + "/"

    val nestedFileKeys = storageKeys(nestedFilePrefix) + blobFallback.keys.filter { it.startsWith(nestedFilePrefix) }
    val nestedDirKeys = storageKeys(nestedDirPrefix) + dirFallback.map(::dirKey).filter { it.startsWith(nestedDirPrefix) }

    nestedFileKeys.forEach { key ->
        storageRemove(key)
        blobFallback.remove(key)
    }
    nestedDirKeys.forEach { key ->
        storageRemove(key)
        dirFallback.remove(key.removePrefix(DIR_PREFIX))
    }

    val dirRemoved = unmarkDirectory(normalized)
    return fileRemoved || dirRemoved || nestedFileKeys.isNotEmpty() || nestedDirKeys.isNotEmpty()
}

actual fun mkdir(path: String): Boolean {
    val normalized = normalizePath(path)
    ensureParentDirectories("$normalized/.dir")
    markDirectory(normalized)
    return true
}

actual fun readLinesSeq(path: String): Sequence<String> = Files.readAllLines(path).asSequence()

actual fun readLines(path: String): List<String> = Files.readAllLines(path)

actual fun platformSeekHandle(): SeekHandle = WasmBrowserSeekHandle

actual fun ioUringHandle(): SeekHandle? = null

actual class FileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
    actual val closeChannelOnMap: Boolean,
) : LongSeries<Byte> {
    private val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly, WasmBrowserSeekHandle)

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
        val bytes = Files.readAllBytes(filename)
        val absolute = (initialOffset + index).toInt()
        val updated =
            if (absolute < bytes.size) {
                bytes.also { it[absolute] = value }
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
    private val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly, WasmBrowserSeekHandle)

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
        val bytes = Files.readAllBytes(filename)
        val absolute = (initialOffset + index).toInt()
        val updated =
            if (absolute < bytes.size) {
                bytes.also { it[absolute] = value }
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
