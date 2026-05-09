package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlin.coroutines.CoroutineContext

/**
 * In-memory [FileOperations] — filesystem as a mutable map.
 * No disk IO. Deterministic. Suitable for test fixtures.
 */
class InMemoryFileOperations(
    private val cwd: String = "/mem",
) : FileOperations {

    private val files = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf<String>()

    override fun readAllLines(filename: String): List<String> =
        readString(filename).lines()

    override fun readAllBytes(filename: String): ByteArray =
        files[filename] ?: throw NoSuchFileException(filename)

    override fun readString(filename: String): String =
        readAllBytes(filename).decodeToString()

    override fun exists(filename: String): Boolean =
        filename in files || filename in dirs

    override fun isFile(path: String): Boolean = path in files
    override fun isDir(path: String): Boolean = path in dirs

    override fun listDir(path: String): List<String> {
        val prefix = path.trimEnd('/') + "/"
        return files.keys.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
    }

    override fun write(filename: String, bytes: ByteArray) {
        ensureParentDirs(filename)
        files[filename] = bytes
    }

    override fun write(filename: String, lines: List<String>) {
        write(filename, lines.joinToString("\n").encodeToByteArray())
    }

    override fun write(filename: String, string: String) {
        write(filename, string.encodeToByteArray())
    }

    override fun mkdirs(path: String) {
        ensureParentDirs("$path/.dir")
        dirs += path
    }

    override fun deleteRecursively(path: String) {
        val prefix = path.trimEnd('/') + "/"
        files.keys.removeAll { it == path || it.startsWith(prefix) }
        dirs.removeAll { it == path || it.startsWith(prefix) }
    }

    override fun cwd(): String = cwd

    override fun resolvePath(vararg parts: String): String =
        parts.fold(cwd) { acc, seg -> "$acc/$seg" }.replace("//", "/")

    override fun createTempDir(prefix: String): String {
        val path = "/tmp/$prefix-${files.size}"
        mkdirs(path)
        return path
    }

    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> {
        val bytes = readAllBytes(fileName)
        return sequence {
            var offset = 0L
            var start = 0L
            val line = mutableListOf<Byte>()
            for (b in bytes) {
                line += b
                offset++
                if (b == '\n'.code.toByte()) {
                    yield(start j line.toByteArray())
                    line.clear()
                    start = offset
                }
            }
            if (line.isNotEmpty()) yield(start j line.toByteArray())
        }
    }

    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (off, arr) -> off j arr.toSeries() }.asIterable()

    override fun readZip(path: String): List<Pair<String, ByteArray>> =
        error("readZip unsupported in InMemoryFileOperations")

    override val key: CoroutineContext.Key<*> get() = FileOperations.Key

    private fun ensureParentDirs(path: String) {
        val parts = path.trimStart('/').split('/')
        var current = ""
        for (i in 0 until parts.lastIndex) {
            current += "/" + parts[i]
            dirs += current
        }
    }
}

class NoSuchFileException(path: String) : RuntimeException("No such file: $path")
