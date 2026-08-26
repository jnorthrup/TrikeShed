package borg.trikeshed.graal.subvm

import borg.trikeshed.btrfs.UserspaceBtrfs
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import borg.trikeshed.vm.Teleported
import org.graalvm.polyglot.io.FileSystem as GraalFileSystem
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicLong

/**
 * Graal's filesystem capability projected onto a UserspaceBtrfs subvolume.
 *
 * java.nio Path is syntax only: no operation delegates to the host filesystem. Every byte and
 * directory mutation lands in [UserspaceBtrfs], and all absolute guest paths remain inside the
 * selected subvolume. Host file access and host socket access stay disabled on the Context.
 */
class TrikeShedGraalVfs(
    private val fileOps: FileOperations = InMemoryFileOperations(cwd = VIRTUAL_ROOT),
    private val btrfsRoot: String = "/trikeshed-graal-btrfs",
    private val liveSubvolume: String = "live",
    private val instanceId: String = "global",
) : GraalFileSystem {
    private val btrfs = UserspaceBtrfs(btrfsRoot, fileOps)
    private val generation = AtomicLong()
    private val inodeSalt = kotlin.random.Random.Default.nextLong()
    @Volatile private var workingDirectory: Path = Path.of(WORKSPACE)
    private val canonicalizer = borg.trikeshed.userspace.containment.createFusePathCanonicalizer(instanceId)

    init {
        if (!btrfs.hasSubvolume(liveSubvolume)) check(btrfs.createSubvolume(liveSubvolume))
        ensureDirectory("workspace")
        ensureDirectory("tmp")
        ensureDirectory("dev")
        if (!btrfs.isFile(liveSubvolume, "dev/null")) check(btrfs.writeFile(liveSubvolume, "dev/null", ByteArray(0)))
    }

    fun put(path: String, bytes: ByteArray) {
        val relative = relativeOf(parsePath(path))
        require(relative.isNotEmpty()) { "cannot write VFS root" }
        check(btrfs.writeFile(liveSubvolume, relative, bytes)) { "VFS write rejected: $path" }
        generation.incrementAndGet()
    }

    fun fetch(path: String): ByteArray? = btrfs.fetchFile(liveSubvolume, relativeOf(parsePath(path)))

    fun snapshot(name: String): Boolean = btrfs.snapshot(liveSubvolume, name)

    fun fetchSnapshot(name: String, path: String): ByteArray? =
        btrfs.fetchFile(name, relativeOf(parsePath(path)))

    fun generation(): Long = generation.get()

    override fun parsePath(uri: URI): Path {
        require(uri.scheme == null || uri.scheme == "file") { "unsupported VFS URI scheme: ${uri.scheme}" }
        return Path.of(uri.path ?: "/")
    }

    override fun parsePath(path: String): Path = Path.of(path.ifEmpty { "." })

    override fun checkAccess(path: Path, modes: Set<out AccessMode>, vararg linkOptions: LinkOption) {
        val relative = relativeOf(path)
        if (!exists(relative)) throw NoSuchFileException(toAbsolutePath(path).toString())
        if (AccessMode.EXECUTE in modes && !isDirectory(relative)) {
            throw java.nio.file.AccessDeniedException(toAbsolutePath(path).toString(), null, "VFS files are not executable")
        }
    }

    override fun createDirectory(path: Path, vararg attrs: FileAttribute<*>) {
        val relative = relativeOf(path)
        if (relative.isEmpty() || exists(relative)) throw FileAlreadyExistsException(toAbsolutePath(path).toString())
        if (!btrfs.createDirectory(liveSubvolume, relative)) throw IOException("cannot create VFS directory: $path")
        generation.incrementAndGet()
    }

    override fun delete(path: Path) {
        val relative = relativeOf(path)
        if (relative.isEmpty()) throw java.nio.file.AccessDeniedException("/")
        if (!exists(relative)) throw NoSuchFileException(path.toString())
        if (!btrfs.deleteFile(liveSubvolume, relative)) throw IOException("cannot delete VFS path: $path")
        generation.incrementAndGet()
    }

    override fun newByteChannel(
        path: Path,
        options: Set<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel {
        val relative = relativeOf(path)
        if (relative == "dev/null") return DevNullByteChannel
        if (relative.isEmpty() || isDirectory(relative)) throw FileSystemException(path.toString(), null, "is a directory")
        val write = StandardOpenOption.WRITE in options || StandardOpenOption.APPEND in options
        val read = StandardOpenOption.READ in options || !write
        val create = StandardOpenOption.CREATE in options || StandardOpenOption.CREATE_NEW in options
        val exists = btrfs.isFile(liveSubvolume, relative)
        if (StandardOpenOption.CREATE_NEW in options && exists) throw FileAlreadyExistsException(path.toString())
        if (!exists && !create) throw NoSuchFileException(path.toString())
        val truncate = write && StandardOpenOption.TRUNCATE_EXISTING in options
        val initial = if (exists && !truncate) btrfs.fetchFile(liveSubvolume, relative) ?: ByteArray(0) else ByteArray(0)
        return VfsByteChannel(
            initial = initial,
            readable = read,
            writable = write,
            append = StandardOpenOption.APPEND in options,
        ) { bytes ->
            if (!btrfs.writeFile(liveSubvolume, relative, bytes)) throw IOException("VFS commit rejected: $path")
            generation.incrementAndGet()
        }
    }

    override fun newDirectoryStream(path: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val relative = relativeOf(path)
        val children = btrfs.listDirectory(liveSubvolume, relative) ?: throw java.nio.file.NotDirectoryException(path.toString())
        val parent = toAbsolutePath(path)
        return object : DirectoryStream<Path> {
            private var open = true
            override fun iterator(): MutableIterator<Path> {
                check(open) { "directory stream closed" }
                val maskedChildren = if (relative == "workspace" || relative == "tmp") {
                    children.map { childName ->
                        val childRelative = if (relative.isEmpty()) childName else "$relative/$childName"
                        val isDir = btrfs.isDirectory(liveSubvolume, childRelative)
                        canonicalizer.canonicalizePath(childName, isDir)
                    }
                } else {
                    children
                }
                val acceptedList = ArrayList<Path>()
                for (childName in maskedChildren) {
                    val childPath = parent.resolve(childName)
                    if (filter.accept(childPath)) {
                        acceptedList.add(childPath)
                    }
                }
                val accepted = acceptedList.iterator()
                return object : MutableIterator<Path> {
                    override fun hasNext(): Boolean = accepted.hasNext()
                    override fun next(): Path = accepted.next()
                    override fun remove(): Unit = throw UnsupportedOperationException("read-only iterator")
                }
            }
            override fun close() { open = false }
        }
    }

    override fun toAbsolutePath(path: Path): Path =
        (if (path.isAbsolute) path else workingDirectory.resolve(path)).normalize()

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path {
        val absolute = toAbsolutePath(path)
        if (!exists(relativeOf(absolute))) throw NoSuchFileException(absolute.toString())
        return absolute
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> {
        val relative = relativeOf(path)
        if (!exists(relative)) throw NoSuchFileException(path.toString())
        val directory = isDirectory(relative)
        val size = if (directory) 0L else (btrfs.fetchFile(liveSubvolume, relative)?.size?.toLong() ?: 0L)
        val zeroTime = FileTime.fromMillis(0)
        val inode = inodeOf(relative)
        val all = linkedMapOf<String, Any>(
            "isRegularFile" to !directory,
            "isDirectory" to directory,
            "isSymbolicLink" to false,
            "isOther" to false,
            "size" to size,
            "fileKey" to inode,
            "lastModifiedTime" to zeroTime,
            "lastAccessTime" to zeroTime,
            "creationTime" to zeroTime,
            "mode" to if (directory) 16877 else 33188, // 040755 | 0100644
            "ino" to inode,
            "dev" to 0L,
            "nlink" to 1,
            "uid" to 0,
            "gid" to 0,
            "ctime" to zeroTime,
        )
        val requested = attributes.substringAfter(':', "*")
        if (requested == "*" || requested.isBlank()) return all
        val names = requested.split(',').toSet()
        return all.filterKeys { it in names }
    }

    override fun setCurrentWorkingDirectory(currentWorkingDirectory: Path) {
        val absolute = toAbsolutePath(currentWorkingDirectory)
        if (!isDirectory(relativeOf(absolute))) throw java.nio.file.NotDirectoryException(absolute.toString())
        workingDirectory = absolute
    }

    override fun getTempDirectory(): Path = Path.of("/tmp")

    private fun ensureDirectory(relative: String) {
        if (!btrfs.isDirectory(liveSubvolume, relative)) check(btrfs.createDirectory(liveSubvolume, relative))
    }

    private fun relativeOf(path: Path): String {
        val absolute = toAbsolutePath(path)
        val text = absolute.toString().replace('\\', '/')
        require(text.startsWith('/')) { "VFS path did not normalize absolute: $path" }
        val relative = text.removePrefix("/").trimEnd('/')
        require(relative.split('/').none { it == ".." }) { "VFS traversal rejected: $path" }

        if (relative.isEmpty()) return ""
        val segments = relative.split('/')
        if (segments.size > 1 && (segments[0] == "workspace" || segments[0] == "tmp")) {
            val unmasked = canonicalizer.resolveOriginal(segments[1]) ?: segments[1]
            val originalSegments = segments.toMutableList()
            originalSegments[1] = unmasked
            return originalSegments.joinToString("/")
        }

        return relative
    }

    private fun inodeOf(relative: String): Long {
        var hash = inodeSalt
        for (i in relative.indices) {
            hash = hash * 31 + relative[i].code.toLong()
        }
        return hash
    }

    private fun exists(relative: String): Boolean =
        relative.isEmpty() || btrfs.isDirectory(liveSubvolume, relative) || btrfs.isFile(liveSubvolume, relative)

    private fun isDirectory(relative: String): Boolean = relative.isEmpty() || btrfs.isDirectory(liveSubvolume, relative)

    companion object {
        const val VIRTUAL_ROOT = "/"
        const val WORKSPACE = "/workspace"
    }
}

/** POSIX-shaped null device required by Python redirect_stdout paths; no native file is exposed. */
private object DevNullByteChannel : SeekableByteChannel {
    override fun isOpen(): Boolean = true
    override fun close() = Unit
    override fun read(dst: ByteBuffer): Int = -1
    override fun write(src: ByteBuffer): Int = src.remaining().also { src.position(src.limit()) }
    override fun position(): Long = 0L
    override fun position(newPosition: Long): SeekableByteChannel = this
    override fun size(): Long = 0L
    override fun truncate(size: Long): SeekableByteChannel = this
}

private class VfsByteChannel(
    initial: ByteArray,
    private val readable: Boolean,
    private val writable: Boolean,
    private val append: Boolean,
    private val commit: (ByteArray) -> Unit,
) : SeekableByteChannel {
    private var data = initial.copyOf(maxOf(initial.size, 32))
    private var length = initial.size
    private var cursor = if (append) length else 0
    private var open = true

    override fun isOpen(): Boolean = open

    override fun close() {
        if (!open) return
        if (writable) commit(data.copyOf(length))
        open = false
    }

    override fun read(dst: ByteBuffer): Int {
        ensureOpen()
        if (!readable) throw NonReadableChannelException()
        if (cursor >= length) return -1
        val count = minOf(dst.remaining(), length - cursor)
        dst.put(data, cursor, count)
        cursor += count
        return count
    }

    override fun write(src: ByteBuffer): Int {
        ensureOpen()
        if (!writable) throw NonWritableChannelException()
        if (append) cursor = length
        val count = src.remaining()
        ensureCapacity(cursor + count)
        src.get(data, cursor, count)
        cursor += count
        if (cursor > length) length = cursor
        return count
    }

    override fun position(): Long { ensureOpen(); return cursor.toLong() }

    override fun position(newPosition: Long): SeekableByteChannel {
        ensureOpen()
        require(newPosition in 0..Int.MAX_VALUE.toLong()) { "invalid position: $newPosition" }
        cursor = newPosition.toInt()
        return this
    }

    override fun size(): Long { ensureOpen(); return length.toLong() }

    override fun truncate(size: Long): SeekableByteChannel {
        ensureOpen()
        if (!writable) throw NonWritableChannelException()
        require(size in 0..Int.MAX_VALUE.toLong()) { "invalid size: $size" }
        if (size < length) length = size.toInt()
        if (cursor > length) cursor = length
        return this
    }

    private fun ensureOpen() { if (!open) throw ClosedChannelException() }

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var capacity = data.size
        while (capacity < required) capacity = maxOf(capacity * 2, required)
        data = data.copyOf(capacity)
    }
}

/** Supervisor-owned Graal guest with an isolated, snapshot-capable UserspaceBtrfs VFS. */
class GraalBtrfsSupervisor(
    override val id: String,
    override val facet: VmFacet,
    budget: Budget = Budget(),
    input: InputStream? = null,
    output: OutputStream? = null,
    error: OutputStream? = output,
    onRootReturn: (RootObservation) -> Unit = {},
) : GuestIsolate {
    val vfs = TrikeShedGraalVfs(instanceId = id)
    /** Exposed so the Hypervisor's LeafTrainer can observe the same in-process guest it trains. */
    val guest = InProcessIsolate(
        id,
        facet,
        budget,
        fileSystem = vfs,
        input = input,
        output = output,
        error = error,
        onRootReturn = onRootReturn,
    )

    fun put(path: String, bytes: ByteArray) = vfs.put(path, bytes)
    fun snapshot(name: String): Boolean = vfs.snapshot(name)
    override val trust: Trust get() = Trust.OWN
    override val bounds: FacetBounds get() = guest.bounds
    override fun eval(source: String, name: String): Teleported = guest.eval(source, name)
    override fun call(root: String, vararg args: Teleported): Teleported = guest.call(root, *args)
    override fun delegate(name: String, fn: (List<Teleported>) -> Teleported) = guest.delegate(name, fn)
    override fun interrupt(): Boolean = guest.interrupt()
    override fun stats(): IsolateStats = guest.stats()
    override val isAlive: Boolean get() = guest.isAlive
    override fun close() = guest.close()
}
