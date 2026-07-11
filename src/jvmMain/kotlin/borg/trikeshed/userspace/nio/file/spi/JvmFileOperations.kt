package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files as NioFiles
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * JVM userspace.nio adapter — pure [java.nio.file] / NIO channels.
 * No java.io.File. Platform filesystem is only this adapter layer.
 */
class JvmFileOperations : FileOperations {

    private val nextFd = AtomicInteger(1)
    private val channels = ConcurrentHashMap<Int, SeekableByteChannel>()

    private fun pathOf(filename: String): Path = Paths.get(filename)

    override fun open(path: String, readOnly: Boolean): Int {
        val flags: Set<OpenOption> = if (readOnly) {
            EnumSet.of(StandardOpenOption.READ)
        } else {
            EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        }
        val channel = NioFiles.newByteChannel(pathOf(path), flags)
        val fd = nextFd.getAndIncrement()
        channels[fd] = channel
        return fd
    }

    override fun close(fd: Int): Int {
        val ch = channels.remove(fd) ?: return -1
        return try {
            ch.close()
            0
        } catch (_: Exception) {
            -1
        }
    }

    override fun size(fd: Int): Long {
        val ch = channels[fd] ?: return -1L
        return try {
            ch.size()
        } catch (_: Exception) {
            -1L
        }
    }

    override fun readAllLines(filename: String): List<String> =
        NioFiles.readAllLines(pathOf(filename), StandardCharsets.UTF_8)

    override fun readAllBytes(filename: String): ByteArray =
        NioFiles.readAllBytes(pathOf(filename))

    override fun readString(filename: String): String =
        NioFiles.readString(pathOf(filename), StandardCharsets.UTF_8)

    override fun write(filename: String, bytes: ByteArray) {
        NioFiles.write(pathOf(filename), bytes)
    }

    override fun write(filename: String, lines: List<String>) {
        NioFiles.write(pathOf(filename), lines, StandardCharsets.UTF_8)
    }

    override fun write(filename: String, string: String) {
        NioFiles.writeString(pathOf(filename), string, StandardCharsets.UTF_8)
    }

    override fun cwd(): String =
        Paths.get("").toAbsolutePath().normalize().toString()

    override fun exists(filename: String): Boolean =
        NioFiles.exists(pathOf(filename))

    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = sequence {
        NioFiles.newInputStream(pathOf(fileName)).use { input ->
            val buffer = ByteArray(bufsize.coerceAtLeast(1))
            var offset = 0L
            var lineStartOffset = 0L
            val lineBuffer = java.io.ByteArrayOutputStream()
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

    override fun listDir(path: String): List<String> {
        val p = pathOf(path)
        if (!NioFiles.isDirectory(p)) return emptyList()
        NioFiles.newDirectoryStream(p).use { stream ->
            return stream.map { it.fileName.toString() }
        }
    }

    override fun isDir(path: String): Boolean =
        NioFiles.isDirectory(pathOf(path))

    override fun isFile(path: String): Boolean =
        NioFiles.isRegularFile(pathOf(path))

    override fun mkdirs(path: String) {
        NioFiles.createDirectories(pathOf(path))
    }

    override fun deleteRecursively(path: String) {
        val p = pathOf(path)
        if (!NioFiles.exists(p)) return
        NioFiles.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                NioFiles.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                NioFiles.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    override fun resolvePath(vararg parts: String): String {
        if (parts.isEmpty()) return cwd()
        var joined = Paths.get(parts[0], *parts.drop(1).toTypedArray())
        val asString = joined.toString()
        if (asString.startsWith("~")) {
            val home = System.getProperty("user.home")
            joined = Paths.get(asString.replaceFirst("~", home))
        }
        return if (joined.isAbsolute) {
            joined.normalize().toString()
        } else {
            Paths.get(cwd()).resolve(joined).normalize().toString()
        }
    }

    override fun readZip(path: String): List<Pair<String, ByteArray>> =
        ZipFile(path).use { zip ->
            zip.entries().asSequence().map { entry ->
                entry.name to zip.getInputStream(entry).use { it.readBytes() }
            }.toList()
        }

    override fun createTempDir(prefix: String): String =
        NioFiles.createTempDirectory(prefix).toAbsolutePath().toString()
}
