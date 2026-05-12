package borg.trikeshed.lib

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.lib.long.LongSeries
import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.WasmFileOperations
import kotlin.random.Random

@JsName("localStorage")external val browserLocalStorage: Storage?
external class Storage {
    val length: Int
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
    fun removeItem(key: String)
    fun key(index: Int): String?
}
const val FILE_PREFIX = "trikeshed:browser:file:"
const val DIR_PREFIX = "trikeshed:browser:dir:"
val blobFallback = linkedMapOf<String, String>()
val dirFallback = linkedSetOf<String>()
val envFallback = linkedMapOf<String, String>()
fun storageOrNull(): Storage? =
    try {
        browserLocalStorage
    } catch (_: Throwable) {
        null
    }
fun normalizePath(path: String): String {
    val normalized = path.replaceChar('\\', '/')
    val parts: MutableList<String> = mutableListOf<String>()
    for (part in normalized.split('/')) {
        when {
            part.isEmpty() || part == "." -> {}
            part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return "/" + parts.joinToString("/")
}
fun parentPath(path: String): String? {
    val normalized: String = normalizePath(path)
    val cut: Int = normalized.lastIndexOf('/')
    return if (cut <= 0) "/"
    else normalized.substring(0, cut)
}
fun fileKey(path: String): String = FILE_PREFIX + normalizePath(path)
fun dirKey(path: String): String = DIR_PREFIX + normalizePath(path)
fun storageGet(key: String): String? =
    storageOrNull()?.let {
        try {
            it.getItem(key)
        } catch (_: Throwable) {
            null
        }
    }
fun storageSet(key: String, value: String): Boolean {
    val storage = storageOrNull() ?: return false
    return try {
        storage.setItem(key, value)
        true
    } catch (_: Throwable) {
        false
    }
}
fun storageRemove(key: String): Boolean {
    val storage = storageOrNull() ?: return false
    return try {
        storage.removeItem(key)
        true
    } catch (_: Throwable) {
        false
    }
}
fun storageKeys(prefix: String): List<String> {
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
fun readBlob(path: String): String? {
    val key = fileKey(path)
    return storageGet(key) ?: blobFallback[key]
}
fun writeBlob(path: String, hex: String) {
    val key = fileKey(path)
    if (!storageSet(key, hex)) {
        blobFallback[key] = hex
    } else {
        blobFallback.remove(key)
    }
}
fun removeBlob(path: String): Boolean {
    val key = fileKey(path)
    val removedStorage = storageRemove(key)
    val removedFallback = blobFallback.remove(key) != null
    return removedStorage || removedFallback
}
fun markDirectory(path: String) {
    val normalized = normalizePath(path)
    if (!storageSet(dirKey(normalized), "1")) {
        dirFallback += normalized
    } else {
        dirFallback.remove(normalized)
    }
}
fun unmarkDirectory(path: String): Boolean {
    val normalized = normalizePath(path)
    val removedStorage = storageRemove(dirKey(normalized))
    val removedFallback = dirFallback.remove(normalized)
    return removedStorage || removedFallback
}
fun directoryExists(path: String): Boolean {
    val normalized = normalizePath(path)
    return storageGet(dirKey(normalized)) != null || normalized in dirFallback
}
fun ensureParentDirectories(path: String) {
    var current = parentPath(path)
    while (current != null && current != "/") {
        markDirectory(current)
        current = parentPath(current)
    }
    markDirectory("/")
}
fun encodeHex(bytes: ByteArray): String {
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
fun decodeHex(value: String): ByteArray {
    if (value.isEmpty()) return ByteArray(0)
    val size = value.length / 2
    return ByteArray(size) { index ->
        val hi = value[index * 2].digitToInt(16)
        val lo = value[index * 2 + 1].digitToInt(16)
        ((hi shl 4) or lo).toByte()
    }
}
object WasmBrowserSeekHandle : SeekHandle {
   data class HandleState(
        val filename: String,
        var position: Long = 0,
    )

   val handles = mutableMapOf<Long, HandleState>()
   var nextHandle = 1L

    override fun open(filename: String, readOnly: Boolean): Long {
        val handle = nextHandle++
        handles[handle] = HandleState(filename = normalizePath(filename))
        return handle
    }

    override fun close(handle: Long) {
        handles.remove(handle)
    }

    override fun pread(handle: Long, dst: ByteRegion, fileOffset: Long): Int {
        val state = handles[handle] ?: return -1
        val bytes = readBlob(state.filename) ?: return -1
        val decoded = decodeHex(bytes)
        val start = fileOffset.toInt().coerceAtLeast(0)
        if (start >= decoded.size) return -1
        val count = minOf(dst.size, decoded.size - start)
        val backing = dst.buffer.array()
        val offset = dst.buffer.arrayOffset() + dst.start
        for (index in 0 until count) {
            backing[offset + index] = decoded[start + index]
        }
        return count
    }

    override fun pwrite(handle: Long, src: ByteSeries, fileOffset: Long): Int {
        val state = handles[handle] ?: return -1
        val bytes = src.toArray()
        // Read-modify-write: localStorage has no partial overwrite
        val existing = decodeHex(readBlob(state.filename) ?: "")
        val start = fileOffset.toInt().coerceAtLeast(0)
        val endExclusive = start + bytes.size
        val updated = ByteArray(maxOf(existing.size, endExclusive))
        existing.copyInto(updated)
        for (i in bytes.indices) {
            updated[start + i] = bytes[i]
        }
        writeBlob(state.filename, encodeHex(updated))
        return bytes.size
    }

    override fun size(handle: Long): Long {
        val state = handles[handle] ?: return -1
        return decodeHex(readBlob(state.filename) ?: "").size.toLong()
    }

    override fun read(handle: Long, dst: ByteRegion): Int {
        val state = handles[handle] ?: return -1
        val bytesRead = pread(handle, dst, state.position)
        if (bytesRead > 0) state.position += bytesRead.toLong()
        return bytesRead
    }

    override fun write(handle: Long, src: ByteSeries): Int {
        val state = handles[handle] ?: return -1
        val bytesWritten = pwrite(handle, src, state.position)
        if (bytesWritten > 0) state.position += bytesWritten.toLong()
        return bytesWritten
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

fun mktemp(): String {
    val name = "/tmp/wasm-${Random.nextLong().toString(16)}.tmp"
    Files.write(name, ByteArray(0))
    return name
}

fun rm(path: String): Boolean {
    val normalized = normalizePath(path)
    val fileRemoved = removeBlob(normalized)

    val nestedFilePrefix = fileKey(normalized).trimEnd('/') + "/"
    val nestedDirPrefix = dirKey(normalized).trimEnd('/') + "/"

    val nestedFileKeys = storageKeys(nestedFilePrefix) + blobFallback.keys.filter { it.startsWith(nestedFilePrefix) }
    val nestedDirKeys =
        storageKeys(nestedDirPrefix) + dirFallback.map(::dirKey).filter { it.startsWith(nestedDirPrefix) }

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

fun mkdir(path: String): Boolean {
    val normalized = normalizePath(path)
    ensureParentDirectories("$normalized/.dir")
    markDirectory(normalized)
    return true
}

fun readLinesSeq(path: String): Sequence<String> = Files.readAllLines(path).asSequence()

fun readLines(path: String): List<String> = Files.readAllLines(path)
actual fun platformSeekHandle(): SeekHandle = WasmBrowserSeekHandle

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

    fun seek(pos: Long) {
        delegate.seek(pos)
    }

    fun put(index: Long, value: Byte) {
        delegate.put(index, value)
    }

    fun readv(requests: Series2<Long, ByteRegion>): IntArray = delegate.readv(requests)
}
