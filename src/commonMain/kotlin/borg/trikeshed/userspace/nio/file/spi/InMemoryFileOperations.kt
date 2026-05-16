package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.SeriesBuffer
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.substringBefore
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import kotlin.coroutines.CoroutineContext

/**
 * In-memory [FileOperations] — filesystem as a map keyed by CharSequence.
 * No disk IO. Deterministic. Suitable for test fixtures.
 */
class InMemoryFileOperations(
    private val cwd: CharSequence = "/mem",
) : FileOperations {

    // LinkedHashMap keyed by CharSequence — no String in the pure zone
    private val files = linkedMapOf<CharSequence, ByteArray>()
    private val dirs = linkedMapOf<CharSequence, Unit>()

    override fun readAllLines(filename: CharSequence): Series<CharSequence> =
        readString(filename).lines().toSeries()

    override fun readAllBytes(filename: CharSequence): ByteArray =
        files[filename] ?: files.entries.firstOrNull { it.key.contentEquals(filename) }?.value
            ?: throw NoSuchFileException(filename)

    override fun readString(filename: CharSequence): CharSequence =
        readAllBytes(filename).decodeToString()

    override fun exists(filename: CharSequence): Boolean =
        files.keys.any { it.contentEquals(filename) } || dirs.keys.any { it.contentEquals(filename) }

    override fun isFile(path: CharSequence): Boolean = files.keys.any { it.contentEquals(path) }
    override fun isDir(path: CharSequence): Boolean = dirs.keys.any { it.contentEquals(path) }

    override fun listDir(path: CharSequence): List<CharSequence> {
        val prefix = path.trimEnd('/').toString() + "/"
        return files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') as CharSequence }
            .distinct()
    }

    override fun write(filename: CharSequence, bytes: ByteArray) {
        ensureParentDirs(filename)
        files[filename] = bytes
    }

    override fun write(filename: CharSequence, lines: Series<CharSequence>) {
        write(filename, lines.view.joinToString("\n").encodeToByteArray())
    }

    override fun write(filename: CharSequence, string: CharSequence) {
        write(filename, string.toString().encodeToByteArray())
    }

    override fun mkdirs(path: CharSequence) {
        ensureParentDirs("$path/.dir")
        dirs[path] = Unit
    }

    override fun deleteRecursively(path: CharSequence) {
        val prefix = path.trimEnd('/').toString() + "/"
        val pathStr = path.toString()
        files.keys.removeAll { it.toString() == pathStr || it.startsWith(prefix) }
        dirs.keys.removeAll { it.toString() == pathStr || it.startsWith(prefix) }
    }

    override fun cwd(): CharSequence = cwd

    override fun resolvePath(vararg parts: CharSequence): CharSequence =
        parts.fold(cwd.toString()) { acc, seg -> "$acc/$seg" }.replace("//", "/")

    override fun createTempDir(prefix: CharSequence): CharSequence {
        val path = "/tmp/$prefix-${files.size}"
        mkdirs(path)
        return path
    }

    override fun streamLines(fileName: CharSequence, bufsize: Int): Sequence<Join<Long, ByteArray>> {
        val bytes = readAllBytes(fileName)
        return sequence {
            var offset = 0L
            var start = 0L
            val line = SeriesBuffer<Byte>()
            for (b in bytes) {
                line += b
                offset++
                if (b == '\n'.code.toByte()) {
                    yield(start j line.snapshot().view.toList().toByteArray())
                    line.clear()
                    start = offset
                }
            }
            if (line.size > 0) yield(start j line.snapshot().view.toList().toByteArray())
        }
    }

    override fun iterateLines(fileName: CharSequence, bufsize: Int): Iterable<Join<Long, ByteArray>> =
        streamLines(fileName, bufsize).asIterable()

    override fun readZip(path: CharSequence): Series2<CharSequence, ByteArray> =
        error("readZip unsupported in InMemoryFileOperations")

    override val key: CoroutineContext.Key<*> get() = FileOperations.Key

    private fun ensureParentDirs(path: CharSequence) {
        val parts = path.trimStart('/').split('/')
        var current = ""
        for (i in 0 until parts.lastIndex) {
            current += "/" + parts[i]
            dirs[current] = Unit
        }
    }
}

class NoSuchFileException(path: CharSequence) : RuntimeException("No such file: $path")
