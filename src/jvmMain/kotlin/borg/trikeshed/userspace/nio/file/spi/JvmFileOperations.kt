package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

class JvmFileOperations : FileOperations {

    override fun open(path: String, readOnly: Boolean): Int {
        val path_ = java.nio.file.Path.of(path)
        val flags = if (readOnly) {
            java.util.EnumSet.of(java.nio.file.StandardOpenOption.READ)
        } else {
            java.util.EnumSet.of(java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.CREATE)
        }
        val channel = java.nio.file.Files.newByteChannel(path_, flags)
        return channel.hashCode() // JVM channels don't expose raw fd, use hashCode as handle
    }

    override fun close(fd: Int): Int = 0 // JVM channel closed via close()

    override fun size(fd: Int): Long {
        // fd is channel hashCode here — lookup from registry if needed
        return 0L
    }

    override fun readAllLines(filename: String): List<String> =
        java.nio.file.Files.readAllLines(java.nio.file.Path.of(filename))

    override fun readAllBytes(filename: String): ByteArray =
        java.nio.file.Files.readAllBytes(java.nio.file.Path.of(filename))

    override fun readString(filename: String): String =
        java.nio.file.Files.readString(java.nio.file.Path.of(filename))

    override fun write(filename: String, bytes: ByteArray) {
        java.nio.file.Files.write(java.nio.file.Path.of(filename), bytes)
    }

    override fun write(filename: String, lines: List<String>) {
        java.nio.file.Files.write(java.nio.file.Path.of(filename), lines)
    }

    override fun write(filename: String, string: String) {
        java.nio.file.Files.writeString(java.nio.file.Path.of(filename), string)
    }

    override fun cwd(): String = java.nio.file.Path.of("").toAbsolutePath().toString()

    override fun exists(filename: String): Boolean =
        java.nio.file.Files.exists(java.nio.file.Path.of(filename))

    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = sequence {
        val f = File(fileName)
        val buffer = ByteArray(bufsize)
        var offset: Long = 0
        var lineStartOffset: Long = 0
        val lineBuffer = ByteArrayOutputStream()
        f.inputStream().use { input ->
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                var mark = 0
                for (i in 0 until bytesRead) {
                    if (buffer[i] == '\n'.code.toByte()) {
                        lineBuffer.write(buffer, mark, i - mark + 1)
                        mark = i + 1
                        yield(lineStartOffset j lineBuffer.toByteArray())
                        lineBuffer.reset()
                        lineStartOffset = offset + i + 1
                    }
                }
                if (mark < bytesRead) lineBuffer.write(buffer, mark, bytesRead - mark)
                offset += bytesRead
            }
            if (lineBuffer.size() > 0) yield(lineStartOffset j lineBuffer.toByteArray())
        }
    }

    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (off, arr) -> off j arr.toSeries() }.asIterable()

    override fun listDir(path: String): List<String> =
        File(path).listFiles()?.map { it.name } ?: emptyList()

    override fun isDir(path: String): Boolean = File(path).isDirectory

    override fun isFile(path: String): Boolean = File(path).isFile

    override fun mkdirs(path: String) { File(path).mkdirs() }

    override fun deleteRecursively(path: String) { File(path).deleteRecursively() }

    override fun resolvePath(vararg parts: String): String = parts.joinToString(File.separator)

    override fun readZip(path: String): List<Pair<String, ByteArray>> = ZipFile(path).use { zip ->
        zip.entries().asSequence().map { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        }.toList()
    }

    override fun createTempDir(prefix: String): String =
        java.nio.file.Files.createTempDirectory(prefix).toAbsolutePath().toString()
}
