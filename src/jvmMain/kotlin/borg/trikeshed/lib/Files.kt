@file:OptIn(ExperimentalTime::class)

package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlin.time.ExperimentalTime


actual object Files {
    private val delegate = JvmFileOperations()

    actual fun readAllLines(filename: String): List<String> = delegate.readAllLines(filename)
    actual fun readAllBytes(filename: String): ByteArray = delegate.readAllBytes(filename)
    actual fun readString(filename: String): String = delegate.readString(filename)
    actual fun write(filename: String, bytes: ByteArray) = delegate.write(filename, bytes)
    actual fun write(filename: String, lines: List<String>) = delegate.write(filename, lines)
    actual fun write(filename: String, string: String) = delegate.write(filename, string)
    actual fun cwd(): String = delegate.cwd()
    actual fun exists(filename: String): Boolean = delegate.exists(filename)
    actual fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = delegate.streamLines(fileName, bufsize)
    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> = delegate.iterateLines(fileName, bufsize)
    actual fun listDir(path: String): List<String> = delegate.listDir(path)
    actual fun isDir(path: String): Boolean = delegate.isDir(path)
    actual fun isFile(path: String): Boolean = delegate.isFile(path)
    actual fun mkdirs(path: String) = delegate.mkdirs(path)
    actual fun deleteRecursively(path: String) = delegate.deleteRecursively(path)
    actual fun resolvePath(vararg parts: String): String = delegate.resolvePath(*parts)
    actual fun readZip(path: String): List<Pair<String, ByteArray>> = delegate.readZip(path)
    actual fun createTempDir(prefix: String): String = delegate.createTempDir(prefix)
}

fun main() {
    val lines = Files.iterateLines("/etc/default/grub", 128 * 1024)
    for (line in lines) {
        val (offset, lineBytes) = line
        val lineStr = lineBytes.asString()
        println("$offset: $lineStr")
    }
}
