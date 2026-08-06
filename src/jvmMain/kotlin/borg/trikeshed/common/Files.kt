package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations

/**
 * JVM [Files] actual — thin facade over userspace.nio [JvmFileOperations].
 * Java NIO lives only in the adapter; this object does not call java.nio/java.io directly.
 */
actual object Files {
    private val ops = JvmFileOperations()

    actual fun readAllLines(filename: String): List<String> = ops.readAllLines(filename)
    actual fun readAllBytes(filename: String): ByteArray = ops.readAllBytes(filename)
    actual fun readString(filename: String): String = ops.readString(filename)
    actual fun write(filename: String, bytes: ByteArray) = ops.write(filename, bytes)
    actual fun write(filename: String, lines: List<String>) = ops.write(filename, lines)
    actual fun write(filename: String, string: String) = ops.write(filename, string)
    actual fun cwd(): String = ops.cwd()
    actual fun exists(filename: String): Boolean = ops.exists(filename)
    actual fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = ops.streamLines(fileName, bufsize)
    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> = ops.iterateLines(fileName, bufsize)

    actual fun listDir(path: String): List<String> = ops.listDir(path)
    actual fun isDir(path: String): Boolean = ops.isDir(path)
    actual fun isFile(path: String): Boolean = ops.isFile(path)
    actual fun mkdirs(path: String) = ops.mkdirs(path)
    actual fun deleteRecursively(path: String) = ops.deleteRecursively(path)
    actual fun resolvePath(vararg parts: String): String = ops.resolvePath(*parts)
    actual fun readZip(path: String): List<Join<String, ByteArray>> = ops.readZip(path)
    actual fun createTempDir(prefix: String): String = ops.createTempDir(prefix)
}
