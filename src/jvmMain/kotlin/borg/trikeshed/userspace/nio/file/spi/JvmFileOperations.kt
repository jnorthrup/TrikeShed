package borg.trikeshed.userspace.nio.file.spi

import kotlinx.coroutines.Dispatchers
import borg.trikeshed.userspace.execute
import borg.trikeshed.userspace.JvmFileSyscalls
import borg.trikeshed.userspace.JvmFileTable
import borg.trikeshed.userspace.JvmNativeDescriptor
import kotlin.coroutines.CoroutineContext

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import java.nio.channels.SeekableByteChannel
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files as NioFiles
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
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
class JvmFileOperations : FileOperations, BoundedFileReader, ChunkedFileReader, DescriptorDuplication {

    override fun duplicateDescriptor(id: Int): Int {
        val original = JvmFileTable.descriptor(id) as? JvmNativeDescriptor
            ?: throw UnsupportedOperationException("Native descriptor duplication is unavailable")
        check(original.retain()) { "File descriptor is closed" }
        try {
            val fd = JvmFileSyscalls.duplicate(original.fd)
            check(fd >= 0) { "File descriptor duplication failed: $fd" }
            return try { JvmFileTable.register(JvmNativeDescriptor(fd)) }
            catch (failure: Throwable) { JvmFileSyscalls.close(fd); throw failure }
        } finally { original.release() }
    }

    override val durability: StorageDurability get() = StorageDurability.DURABLE

    override fun acquireExclusiveLease(path: String): FileLease = synchronized(leases) {
        // POSIX record locks are process-associated: closing a second descriptor for the
        // same file can release the first descriptor's lock. Identify contenders before
        // opening, including symlink and hard-link aliases. Serialize creation so two
        // differently cased names of an absent file cannot race on a case-insensitive FS.
        val key = canonicalPath(path)
        check(key !in leases) { "Filesystem lease is already held: $path" }
        val attributes = try { NioFiles.readAttributes(pathOf(key), BasicFileAttributes::class.java) }
            catch (_: NoSuchFileException) { null }
        if (attributes != null) for ((heldPath, held) in leases) {
            val identity = attributes.fileKey()
            val same = if (identity != null && held.fileKey != null) identity == held.fileKey
                else NioFiles.isSameFile(pathOf(key), pathOf(heldPath))
            check(!same) { "Filesystem lease is already held: $path" }
        }
        val owner = LeaseIdentity(attributes?.fileKey())
        leases[key] = owner
        val channel = try { FileChannel.open(pathOf(key), StandardOpenOption.CREATE, StandardOpenOption.WRITE) }
        catch (failure: Throwable) { leases.remove(key, owner); throw failure }
        try {
            if (owner.fileKey == null)
                owner.fileKey = NioFiles.readAttributes(pathOf(key), BasicFileAttributes::class.java).fileKey()
            val lock = channel.tryLock() ?: error("Filesystem lease is already held: $path")
            object : FileLease {
                override fun close() = synchronized(leases) {
                    try {
                        if (lock.isValid) lock.release()
                        if (channel.isOpen) channel.close()
                    } finally {
                        if (!lock.isValid && !channel.isOpen) leases.remove(key, owner)
                    }
                }
            }
        } catch (failure: Throwable) {
            try { channel.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            if (!channel.isOpen) leases.remove(key, owner)
            throw failure
        }
    }

    private val nextFd = AtomicInteger(1)
    private val channels = ConcurrentHashMap<Int, SeekableByteChannel>()

    private fun pathOf(filename: String): Path = Paths.get(filename)

    override fun move(source: String, destination: String) {
        NioFiles.move(pathOf(source), pathOf(destination))
    }

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

    override fun readBounded(path: String, maxBytes: Int): ByteArray {
        require(maxBytes >= 0 && maxBytes < Int.MAX_VALUE) { "Invalid file read limit" }
        // Read from one opened handle; a size(path) check followed by readAllBytes can
        // allocate beyond the bound if another writer changes the file between calls.
        return NioFiles.newInputStream(pathOf(path)).use { input ->
            val bytes = input.readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) throw BoundedReadLimitException(maxBytes)
            bytes
        }
    }

    override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long =
        borg.trikeshed.userspace.nio.channels.FileChannel.openReadNonblocking(path).use { input ->
            require(limit >= 0)
            val opened = input as? borg.trikeshed.userspace.nio.channels.UringFileChannel
                ?: throw UnsupportedOperationException("Opened file identity is unavailable")
            val identity = opened.identity()
            val buffer = borg.trikeshed.userspace.nio.ByteBuffer.allocate(64 * 1024)
            var total = 0L
            while (true) {
                val remaining = limit - total
                val request = if (remaining >= buffer.array().size) buffer.array().size else (remaining + 1).toInt()
                buffer.clear()
                buffer.limit(request)
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0) { "File read stalled" }
                total += count
                consume(buffer.array(), count)
                if (total > limit) break
            }
            val ring = borg.trikeshed.userspace.nio.channels.UringChannels.open()
            val pathIdentity = try {
                val op = borg.trikeshed.userspace.UringOp.Companion.Submissions.statx(
                    borg.trikeshed.userspace.UringOp.AT_FDCWD, path, 0, borg.trikeshed.userspace.UringOp.STATX_BASIC_STATS, 0)
                val result = ring.execute(op)
                if (result != 0) throw borg.trikeshed.userspace.nio.UringIOException(borg.trikeshed.userspace.UringOp.STATX, result)
                borg.trikeshed.userspace.nio.channels.fileIdentity(borg.trikeshed.userspace.Statx.of(op))
            } finally { ring.closeNow() }
            if (opened.identity() != identity || pathIdentity != identity) throw FileChangedException()
            total
        }

    override fun readString(filename: String): String =
        NioFiles.readString(pathOf(filename), StandardCharsets.UTF_8)

    override fun write(filename: String, bytes: ByteArray) {
        NioFiles.write(pathOf(filename), bytes)
    }

    /**
     * Publish immutable bytes through a same-directory temporary file. Forcing
     * the temporary file before the atomic move makes the content durable;
     * forcing the parent afterwards makes the directory entry durable.
     */
    override fun writeAtomically(filename: String, bytes: ByteArray) {
        val target = pathOf(filename).toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) {
            "Atomic write target has no parent: $target"
        }
        NioFiles.createDirectories(parent)
        val temp = NioFiles.createTempFile(parent, ".${target.fileName}.", ".tmp")
        try {
            FileChannel.open(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    check(channel.write(buffer) > 0) { "Short atomic write to $temp" }
                }
                channel.force(true)
            }
            NioFiles.move(
                temp,
                target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            FileChannel.open(parent, StandardOpenOption.READ).use { directory ->
                directory.force(true)
            }
        } finally {
            NioFiles.deleteIfExists(temp)
        }
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
        // One entry whose NAME the platform charset cannot decode used to abort the whole
        // listing, and with it every caller's walk. `sun.jnu.encoding` follows the process
        // locale, which is ASCII in a bare container (no LANG), so a single non-ASCII
        // filename anywhere under the repo — `.venv/bin/…` is how we found it — threw
        // "Malformed input or input contains unmappable characters" and reduced the whole
        // Couch worktree plane to nothing. A name we cannot read is one entry lost, never
        // the directory. UnixPath holds raw bytes and decodes lazily, so the throw usually
        // lands on toString(); the iterator guard covers the case where it lands earlier.
        val names = ArrayList<String>()
        var undecodable = 0
        var lastError: String? = null
        NioFiles.newDirectoryStream(p).use { stream ->
            val entries = stream.iterator()
            while (undecodable <= MAX_UNDECODABLE_ENTRIES) {
                val entry = try {
                    if (!entries.hasNext()) break
                    entries.next()
                } catch (t: Throwable) {
                    undecodable++; lastError = t.message ?: t.toString(); continue
                }
                try {
                    names.add(entry.fileName.toString())
                } catch (t: Throwable) {
                    undecodable++; lastError = t.message ?: t.toString()
                }
            }
        }
        if (undecodable > 0) {
            System.err.println(
                "[FS] listDir($path): $undecodable entr${if (undecodable == 1) "y" else "ies"} skipped, " +
                    "name undecodable under sun.jnu.encoding=${System.getProperty("sun.jnu.encoding")} " +
                    "(${names.size} returned; last: $lastError)"
            )
        }
        return names
    }

    override fun isDir(path: String): Boolean =
        NioFiles.isDirectory(pathOf(path))

    override fun isFile(path: String): Boolean =
        NioFiles.isRegularFile(pathOf(path))

    override fun mkdirs(path: String) {
        NioFiles.createDirectories(pathOf(path))
    }

    override fun createDirectory(path: String) {
        NioFiles.createDirectory(pathOf(path))
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

    override fun canonicalPath(path: String): String {
        val expanded = if (path.startsWith("~")) path.replaceFirst("~", System.getProperty("user.home")) else path
        var parent = pathOf(expanded).toAbsolutePath()
        val suffix = mutableListOf<Path>()
        while (true) {
            try {
                NioFiles.readAttributes(parent, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                break
            } catch (_: NoSuchFileException) { }
            val name = requireNotNull(parent.fileName) { "Filesystem path has no existing ancestor" }
            require(name.toString() != "..") { "Cannot canonicalize a missing parent component" }
            if (name.toString() != ".") suffix.add(name)
            parent = requireNotNull(parent.parent) { "Filesystem path has no existing ancestor" }
        }
        var canonical = parent.toRealPath()
        for (name in suffix.asReversed()) canonical = canonical.resolve(name)
        return canonical.toString()
    }

    override fun readZip(path: String): List<Join<String, ByteArray>> =
        ZipFile(path).use { zip ->
            zip.entries().asSequence().map { entry ->
                entry.name j zip.getInputStream(entry).use { it.readBytes() }
            }.toList()
        }

    override fun createTempDir(prefix: String): String =
        NioFiles.createTempDirectory(prefix).toAbsolutePath().toString()

    override fun lastModified(filename: String): Long =
        runCatching { NioFiles.getLastModifiedTime(Paths.get(filename)).toMillis() }.getOrDefault(0L)

    companion object {
        private class LeaseIdentity(var fileKey: Any?)
        private val leases = mutableMapOf<String, LeaseIdentity>()
        /**
         * Cap on entries [listDir] will skip in one directory before it stops advancing the
         * iterator. Skipping is only safe while the iterator stays usable; the cap is what
         * keeps an iterator that throws unconditionally from spinning forever.
         */
        private const val MAX_UNDECODABLE_ENTRIES = 64
    }
}

actual val fileIoContext: CoroutineContext get() = Dispatchers.IO
