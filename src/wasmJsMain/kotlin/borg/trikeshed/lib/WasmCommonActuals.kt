package borg.trikeshed.lib
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.platform.HostSystem

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.lib.long.LongSeries
import borg.trikeshed.userspace.nio.IOException
import kotlin.random.Random

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(key, value) => { try { if (typeof localStorage !== 'undefined') { localStorage.setItem(key, value); return true; } } catch (e) {} return false; }")
private external fun jsStorageSet(key: String, value: String): Boolean

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(key) => typeof localStorage !== 'undefined' ? localStorage.getItem(key) : null")
private external fun jsStorageGet(key: String): String?

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(key) => { try { if (typeof localStorage !== 'undefined') { localStorage.removeItem(key); return true; } } catch (e) {} return false; }")
private external fun jsStorageRemove(key: String): Boolean

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => typeof localStorage !== 'undefined' ? localStorage.length : 0")
private external fun jsStorageLength(): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(index) => typeof localStorage !== 'undefined' ? localStorage.key(index) : null")
private external fun jsStorageKey(index: Int): String?

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => typeof localStorage !== 'undefined'")
private external fun jsStorageAvailable(): Boolean

private inline fun <T> storageRead(read: () -> T): T = try { read() } catch (failure: Throwable) {
    throw IOException("Browser storage read failed", failure)
}

const val FILE_PREFIX = "trikeshed:browser:file:"
const val DIR_PREFIX = "trikeshed:browser:dir:"
val blobFallback = linkedMapOf<String, String>()
val dirFallback = linkedSetOf<String>()
val envFallback = linkedMapOf<String, String>()

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
fun storageGet(key: String): String? = storageRead { jsStorageGet(key) }
fun storageSet(key: String, value: String): Boolean = jsStorageSet(key, value)
fun storageRemove(key: String): Boolean = jsStorageRemove(key)
fun storageKeys(prefix: String): List<String> {
    val len = storageRead { jsStorageLength() }
    val keys = mutableListOf<String>()
    for (index in 0 until len) {
        val key = storageRead { jsStorageKey(index) }
        if (key != null && key.startsWith(prefix)) keys += key
    }
    return keys
}
private fun blobValue(path: String): String? {
    val key = fileKey(path)
    return storageGet(key) ?: blobFallback[key]
}
fun readBlob(path: String): String? = blobValue(path)?.substringAfter(':')
private fun modifiedKey(path: String): String = "trikeshed:browser:mtime:" + normalizePath(path)

fun blobModified(path: String): Long {
    val value = blobValue(path) ?: return 0L
    return if (':' in value) value.substringBefore(':').toLongOrNull() ?: 0L
    else storageGet(modifiedKey(path))?.toLongOrNull() ?: 0L
}

fun writeBlob(path: String, hex: String) {
    val key = fileKey(path)
    // Hex contains no colon. One value replaces bytes and time together;
    // older raw-hex records remain readable with their separate timestamp.
    val value = "${HostSystem.currentTimeMillis()}:$hex"
    if (storageRead { jsStorageAvailable() }) {
        if (!storageSet(key, value)) throw IOException("Browser storage write failed: $path")
        blobFallback.remove(key)
    } else blobFallback[key] = value
}
fun removeBlob(path: String): Boolean {
    val key = fileKey(path)
    val removedStorage = storageGet(key) != null
    if (removedStorage && !storageRemove(key)) throw IOException("Browser storage removal failed: $path")
    val removedFallback = blobFallback.remove(key) != null
    val modified = modifiedKey(path)
    if (storageGet(modified) != null && !storageRemove(modified)) throw IOException("Browser timestamp removal failed: $path")
    return removedStorage || removedFallback
}
fun markDirectory(path: String) {
    val normalized = normalizePath(path)
    if (storageGet(dirKey(normalized)) != null) return
    if (storageRead { jsStorageAvailable() }) {
        if (!storageSet(dirKey(normalized), "1")) throw IOException("Browser directory write failed: $path")
        dirFallback.remove(normalized)
    } else dirFallback += normalized
}
fun unmarkDirectory(path: String): Boolean {
    val normalized = normalizePath(path)
    val removedStorage = storageGet(dirKey(normalized)) != null
    if (removedStorage && !storageRemove(dirKey(normalized))) throw IOException("Browser directory removal failed: $path")
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

fun rm(path: String): Boolean {
    val normalized = normalizePath(path)
    val fileRemoved = removeBlob(normalized)

    val nestedFilePrefix = fileKey(normalized).trimEnd('/') + "/"
    val nestedDirPrefix = dirKey(normalized).trimEnd('/') + "/"

    val nestedFileKeys = storageKeys(nestedFilePrefix) + blobFallback.keys.filter { it.startsWith(nestedFilePrefix) }
    val nestedDirKeys =
        storageKeys(nestedDirPrefix) + dirFallback.map(::dirKey).filter { it.startsWith(nestedDirPrefix) }

    nestedFileKeys.forEach { key ->
        removeBlob(key.removePrefix(FILE_PREFIX))
    }
    nestedDirKeys.forEach { key ->
        unmarkDirectory(key.removePrefix(DIR_PREFIX))
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

fun readLinesSeq(path: String): Sequence<String> = borg.trikeshed.common.Files.readAllLines(path).asSequence()

fun readLines(path: String): List<String> = borg.trikeshed.common.Files.readAllLines(path)

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

fun mktemp(): String {
    val name = "/tmp/wasm-${kotlin.random.Random.nextLong().toString(16)}.tmp"
    borg.trikeshed.common.Files.write(name, ByteArray(0))
    return name
}

private fun String.replaceChar(old: Char, new: Char): String {
    val out = StringBuilder(length)
    for (index in indices) {
        val c = this[index]
        out.append(if (c == old) new else c)
    }
    return out.toString()
}
