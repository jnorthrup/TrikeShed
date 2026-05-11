package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.*
import kotlin.random.Random

/**
 * Wasm platform FileOperations — inlined from the old actual object Files.
 * Uses OPFS / IndexedDB blob storage for the Wasm browser environment.
 */
class WasmFileOperations : FileOperations {

    override fun readAllLines(filename: String): List<String> =
        readString(filename).replaceChar('\r', '\n').split('\n').let { parts ->
            if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        }

    override fun readAllBytes(filename: String): ByteArray =
        readBlob(filename)?.let(::decodeHex) ?: ByteArray(0)

    override fun readString(filename: String): String = readAllBytes(filename).decodeToString()

    override fun write(filename: String, bytes: ByteArray) {
        ensureParentDirectories(filename)
        writeBlob(filename, encodeHex(bytes))
    }

    override fun write(filename: String, lines: List<String>) { write(filename, lines.joinToString("\n")) }

    override fun write(filename: String, string: String) { write(filename, string.encodeToByteArray()) }

    override fun cwd(): String = "/"

    override fun exists(filename: String): Boolean =
        readBlob(filename) != null || directoryExists(filename)

    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        streamByteLines(readAllBytes(fileName))

    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (offset, bytes) -> offset j bytes.toSeries() }.asIterable()

    override fun listDir(path: String): List<String> {
        val normalized = normalizePath(path).trimEnd('/') + "/"
        val fileKeys = storageKeys(fileKey(normalized)) + blobFallback.keys.filter { it.startsWith(fileKey(normalized)) }
        val dirKeys = storageKeys(dirKey(normalized)) + dirFallback.map(::dirKey).filter { it.startsWith(dirKey(normalized)) }
        val entries = mutableListOf<String>()
        for (key in fileKeys) entries.add(key.removePrefix(fileKey(normalized)).substringBefore('/'))
        for (key in dirKeys) entries.add(key.removePrefix(dirKey(normalized)).substringBefore('/'))
        return entries.distinct()
    }

    override fun isDir(path: String): Boolean = directoryExists(path)
    override fun isFile(path: String): Boolean = readBlob(path) != null
    override fun mkdirs(path: String) { ensureParentDirectories(path) }
    override fun deleteRecursively(path: String) { rm(path) }

    override fun resolvePath(vararg parts: String): String = normalizePath(parts.joinToString("/"))

    override fun readZip(path: String): List<Pair<String, ByteArray>> = TODO("readZip WASM")

    override fun createTempDir(prefix: String): String =
        "/tmp/$prefix-${Random.nextLong().toString(16)}"
}

private fun streamByteLines(bytes: ByteArray): Sequence<Join<Long, ByteArray>> = sequence {
    if (bytes.isEmpty()) return@sequence
    var start = 0
    var index = 0
    while (index < bytes.size) {
        if (bytes[index] == '\n'.code.toByte()) {
            yield(start.toLong() j bytes.copyOfRange(start, index + 1))
            start = index + 1
        }
        index++
    }
    if (start < bytes.size) yield(start.toLong() j bytes.copyOfRange(start, bytes.size))
}

private fun String.replaceChar(old: Char, new: Char): String {
    val out = StringBuilder(length)
    for (index in indices) {
        val c = this[index]
        out.append(if (c == old) new else c)
    }
    return out.toString()
}
