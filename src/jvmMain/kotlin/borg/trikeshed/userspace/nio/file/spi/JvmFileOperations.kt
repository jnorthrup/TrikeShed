package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class JvmFileOperations : FileOperations {

    override fun readAllLines(filename: CharSequence): Series<CharSequence> =
        java.nio.file.Files.readAllLines(java.nio.file.Path.of(filename.toString())).toSeries()

    override fun readAllBytes(filename: CharSequence): ByteArray =
        java.nio.file.Files.readAllBytes(java.nio.file.Path.of(filename.toString()))

    override fun readString(filename: CharSequence): CharSequence =
        java.nio.file.Files.readString(java.nio.file.Path.of(filename.toString()))

    override fun write(filename: CharSequence, bytes: ByteArray) {
        val f = java.io.File(filename.toString())
        f.parentFile?.mkdirs()
        java.nio.file.Files.write(f.toPath(), bytes)
    }

    override fun write(filename: CharSequence, lines: Series<CharSequence>) {
        val f = java.io.File(filename.toString())
        f.parentFile?.mkdirs()
        java.nio.file.Files.write(f.toPath(), lines.view.map { it.toString() })
    }

    override fun write(filename: CharSequence, string: CharSequence) {
        val f = java.io.File(filename.toString())
        f.parentFile?.mkdirs()
        java.nio.file.Files.writeString(f.toPath(), string)
    }

    override fun cwd(): String = java.nio.file.Path.of("").toAbsolutePath().toString()

    override fun exists(filename: CharSequence): Boolean =
        java.nio.file.Files.exists(java.nio.file.Path.of(filename.toString()))

    override fun streamLines(fileName: CharSequence, bufsize: Int): Sequence<Join<Long, ByteArray>> = sequence {
        val f = File(fileName.toString())
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

    override fun iterateLines(fileName: CharSequence, bufsize: Int): Iterable<Join<Long, ByteArray>> =
        streamLines(fileName, bufsize).asIterable()

    override fun listDir(path: CharSequence): List<CharSequence> =
        File(path.toString()).listFiles()?.map { it.name } ?: emptyList()

    override fun isDir(path: CharSequence): Boolean = File(path.toString()).isDirectory

    override fun isFile(path: CharSequence): Boolean = File(path.toString()).isFile

    override fun mkdirs(path: CharSequence) { File(path.toString()).mkdirs() }

    override fun deleteRecursively(path: CharSequence) { File(path.toString()).deleteRecursively() }

    override fun resolvePath(vararg parts: CharSequence): String = parts.joinToString(File.separator)

    override fun readZip(path: CharSequence): Series2<CharSequence, ByteArray> {
        val result = mutableListOf<Join<CharSequence, ByteArray>>()
        ZipInputStream(File(path.toString()).inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    result.add(entry.name j bytes)
                }
                entry = zis.nextEntry
            }
        }
        return result.toSeries()
    }

    override fun createTempDir(prefix: CharSequence): String =
        java.nio.file.Files.createTempDirectory(prefix.toString()).toAbsolutePath().toString()
}
