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
    private val openFiles = mutableMapOf<Int, String>()

    override fun open(path: String, readOnly: Boolean): Int {
        if (!exists(path)) throw NoSuchFileException(path)
        val fd = openFiles.size + 1
        openFiles[fd] = path
        return fd
    }

    override fun close(fd: Int): Int {
        openFiles.remove(fd)
        return 0
    }

    override fun size(fd: Int): Long {
        val path = openFiles[fd] ?: return 0L
        return files[path]?.size?.toLong() ?: 0L
    }

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
<<<<<<< HEAD
        // ⚡ Bolt: Avoid intermediate sequence, filter, and map allocations.
        // Use a direct loop with LinkedHashSet to preserve order and distinctness with zero intermediate object overhead.
        val result = LinkedHashSet<String>()
        for (key in files.keys) {
            if (key.startsWith(prefix)) {
                val name = key.removePrefix(prefix).substringBefore('/')
                if (name.isNotEmpty()) {
                    result.add(name)
                }
            }
        }
        for (dir in dirs) {
            if (dir.startsWith(prefix)) {
                val name = dir.removePrefix(prefix).substringBefore('/')
                if (name.isNotEmpty()) {
                    result.add(name)
                }
=======
        val result = LinkedHashSet<String>()
        for (key in files.keys) {
            if (key.startsWith(prefix)) {
                val mapped = key.removePrefix(prefix).substringBefore('/')
                if (mapped.isNotEmpty()) result.add(mapped)
            }
        }
        for (key in dirs) {
            if (key.startsWith(prefix)) {
                val mapped = key.removePrefix(prefix).substringBefore('/')
                if (mapped.isNotEmpty()) result.add(mapped)
>>>>>>> origin/bolt/sequence-allocation-optimizations-7716736111111624820
            }
        }
        return result.toList()
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
        files.entries.removeAll { it.key == path || it.key.startsWith(prefix) }
        dirs.removeAll { it == path || it.startsWith(prefix) }
    }

    override fun cwd(): String = cwd

    override fun resolvePath(vararg parts: String): String {
        if (parts.isEmpty()) return cwd
        val start = if (parts.first().startsWith('/')) parts.first() else "$cwd/${parts.first()}"
        return parts.drop(1).fold(start) { acc, seg -> "$acc/$seg" }.replace("//", "/")
    }

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

    override fun readZip(path: String): List<Join<String, ByteArray>> =
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
