package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series

open class FakeFileOperations : FileOperations {
    override fun open(path: String, readOnly: Boolean): Int = 0
    override fun readAllLines(filename: String): List<String> = emptyList()
    override fun readAllBytes(filename: String): ByteArray = ByteArray(0)
    override fun readString(filename: String): String = ""
    override fun write(filename: String, bytes: ByteArray) {}
    override fun write(filename: String, lines: List<String>) {}
    override fun write(filename: String, string: String) {}
    override fun cwd(): String = ""
    override fun exists(filename: String): Boolean = true
    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = emptySequence()
    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> = emptyList()
    override fun listDir(path: String): List<String> = emptyList()
    override fun isDir(path: String): Boolean = true
    override fun isFile(path: String): Boolean = true
    override fun mkdirs(path: String) {}
    override fun deleteRecursively(path: String) {}
    override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
    override fun readZip(path: String): List<Pair<String, ByteArray>> = emptyList()
    override fun createTempDir(prefix: String): String = ""
    override fun close(fd: Int): Int = 0
    override fun size(fd: Int): Long = 0
}
