package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.*
import kotlin.random.Random

/**
 * Wasm platform FileOperations — inlined from the old actual object Files.
 * Uses OPFS / IndexedDB blob storage for the Wasm browser environment.
 */
class WasmFileOperations : FileOperations {
    override fun readAllLines(filename: CharSequence): Series<CharSequence> {
        TODO("Not yet implemented")
    }

    override fun readAllBytes(filename: CharSequence): ByteArray =
        readBlob(filename)?.let(::decodeHex) ?: ByteArray(0)

    override fun readString(filename: CharSequence): String = readAllBytes(filename).decodeToString()

    override fun write(filename: CharSequence, bytes: ByteArray) {
        ensureParentDirectories(filename)
        writeBlob(filename, encodeHex(bytes))
    }

    override fun write(filename: CharSequence, lines: Series<CharSequence>) {
        TODO("Not yet implemented")
    }


    override fun write(filename: CharSequence, string: CharSequence) { write(filename, string.toString().encodeToByteArray()) }

    override fun cwd(): String = "/"

    override fun exists(filename: CharSequence): Boolean =
        readBlob(filename) != null || directoryExists(filename)

    override fun streamLines(fileName: CharSequence, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        streamByteLines(readAllBytes(fileName))

    override fun iterateLines(
        fileName: CharSequence,
        bufsize: Int
    ): Iterable<Join<Long, ByteArray>> {
        TODO("Not yet implemented")
    }
//

    override fun listDir(path: CharSequence): List<CharSeries> {
        val normalized = normalizePath(path).toString().trimEnd('/') + "/"
        val fileKeys = storageKeys(fileKey(normalized)) + blobFallback.keys.filter { it.startsWith(fileKey(normalized)) }
        val dirKeys = storageKeys(dirKey(normalized)) + dirFallback.map(::dirKey).filter { it.startsWith(dirKey(normalized)) }
        val entries: MutableSeries<CharSequence> = emptySeries<CharSequence>() as MutableSeries<CharSequence>
        for (key in fileKeys) entries.add(key.removePrefix(fileKey(normalized).toString()).substringBefore('/'))
        for (key in dirKeys) entries.add(key.removePrefix(dirKey(normalized).toString()).substringBefore('/'))
        return (entries α {it.cs }).view.distinct()
    }

    override fun isDir(path: CharSequence): Boolean = directoryExists(path)
    override fun isFile(path: CharSequence): Boolean = readBlob(path) != null
    override fun mkdirs(path: CharSequence) { ensureParentDirectories(path) }
    override fun deleteRecursively(path: CharSequence) { rm(path) }

    override fun resolvePath(vararg parts: CharSequence): String = normalizePath(parts.joinToString("/")).toString()
    override fun readZip(path: CharSequence): Series2<CharSequence, ByteArray> {
        TODO("Not yet implemented")
    }


    override fun createTempDir(prefix: CharSequence): String =
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
