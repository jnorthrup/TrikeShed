package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.decodeHex
import borg.trikeshed.lib.directoryExists
import borg.trikeshed.lib.encodeHex
import borg.trikeshed.lib.ensureParentDirectories
import borg.trikeshed.lib.normalizePath
import borg.trikeshed.lib.readBlob
import borg.trikeshed.lib.rm
import borg.trikeshed.lib.streamByteLines
import borg.trikeshed.lib.writeBlob
import borg.trikeshed.lib.storageKeys
import borg.trikeshed.lib.fileKey
import borg.trikeshed.lib.dirKey
import borg.trikeshed.lib.dirFallback
import borg.trikeshed.lib.blobFallback
import kotlin.random.Random

private fun String.normalizeCrLf(): String {
    val out = StringBuilder(length)
    var index = 0
    while (index < length) {
        if (this[index] == '\r' && index + 1 < length && this[index + 1] == '\n') {
            out.append('\n')
            index += 2
        } else {
            out.append(this[index])
            index++
        }
    }
    return out.toString()
}

actual object Files {
    actual fun readAllLines(filename: String): List<String> =
        readString(filename).normalizeCrLf().split('\n').let { parts ->
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

    actual fun listDir(path: String): List<String> {
        val normalized = normalizePath(path).trimEnd('/') + "/"
        val fileKeys = storageKeys(fileKey(normalized)) + blobFallback.keys.filter { it.startsWith(fileKey(normalized)) }
        val dirKeys = storageKeys(dirKey(normalized)) + dirFallback.map(::dirKey).filter { it.startsWith(dirKey(normalized)) }
        val entries = mutableListOf<String>()
        for (key in fileKeys) entries.add(key.removePrefix(fileKey(normalized)).substringBefore('/'))
        for (key in dirKeys) entries.add(key.removePrefix(dirKey(normalized)).substringBefore('/'))
        return entries.distinct()
    }

    actual fun isDir(path: String): Boolean = directoryExists(path)
    actual fun isFile(path: String): Boolean = readBlob(path) != null
    actual fun mkdirs(path: String) { ensureParentDirectories(path) }
    actual fun deleteRecursively(path: String) { rm(path) }

    actual fun resolvePath(vararg parts: String): String =
        normalizePath(parts.joinToString("/"))

    actual fun readZip(path: String): List<Join<String, ByteArray>> = error("readZip WASM not supported")

    actual fun createTempDir(prefix: String): String =
        "/tmp/$prefix-${Random.nextLong().toString(16)}"
}
