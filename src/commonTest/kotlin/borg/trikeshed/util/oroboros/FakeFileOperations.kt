package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series

/**
 * Stateful in-memory [FileOperations] test double: files by path, directory
 * set, and a special-file set whose reads fail (simulating non-regular files).
 */
open class FakeFileOperations : FileOperations {
    val files = mutableMapOf<String, ByteArray>()
    val dirs = mutableSetOf<String>()
    val specialFiles = mutableSetOf<String>()

    override fun open(path: String, readOnly: Boolean): Int = throw UnsupportedOperationException()
    override fun readAllLines(filename: String): List<String> = throw UnsupportedOperationException()
    override fun readAllBytes(filename: String): ByteArray {
        if (specialFiles.contains(filename)) throw RuntimeException("Cannot read special file")
        return files[filename] ?: throw RuntimeException("File not found: $filename")
    }
    override fun readString(filename: String): String = throw UnsupportedOperationException()
    override fun write(filename: String, bytes: ByteArray) { files[filename] = bytes }
    override fun write(filename: String, lines: List<String>) { throw UnsupportedOperationException() }
    override fun write(filename: String, string: String) { throw UnsupportedOperationException() }
    override fun cwd(): String = "/tmp/testroot"
    override fun exists(filename: String): Boolean = files.containsKey(filename) || dirs.contains(filename) || specialFiles.contains(filename)
    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = throw UnsupportedOperationException()
    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> = throw UnsupportedOperationException()
    override fun listDir(path: String): List<String> {
        val prefix = if (path.endsWith("/")) path else "$path/"
        val result = mutableSetOf<String>()
        val allPaths = files.keys + dirs + specialFiles
        for (p in allPaths) {
            if (p != path && p.startsWith(prefix)) {
                val rel = p.substring(prefix.length)
                val slash = rel.indexOf('/')
                if (slash == -1) {
                    result.add(rel)
                } else {
                    result.add(rel.substring(0, slash))
                }
            }
        }
        return result.toList()
    }
    override fun isDir(path: String): Boolean = dirs.contains(path)
    override fun isFile(path: String): Boolean = files.containsKey(path)
    override fun mkdirs(path: String) { dirs.add(path) }
    override fun deleteRecursively(path: String) { throw UnsupportedOperationException() }
    override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
    override fun readZip(path: String): List<Join<String, ByteArray>> = throw UnsupportedOperationException()
    override fun createTempDir(prefix: String): String = throw UnsupportedOperationException()
    override fun close(fd: Int): Int = throw UnsupportedOperationException()
    override fun size(fd: Int): Long = throw UnsupportedOperationException()
}
