package borg.trikeshed.graal.subvm

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.btrfs.UserspaceBtrfs
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
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
import java.lang.ref.WeakReference

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
    cas: CasStore? = null,
) : GraalFileSystem {
    private val btrfs = UserspaceBtrfs(btrfsRoot, fileOps, cas)
    private val generation = AtomicLong()
    private val fileLock = Any()
    private val openFiles = mutableMapOf<String, WeakReference<VfsFile>>()
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

    fun put(path: String, bytes: ByteArray): Unit = synchronized(fileLock) {
        val relative = relativeOf(parsePath(path))
        require(relative.isNotEmpty()) { "cannot write VFS root" }
        val captured = bytes.copyOf()
        check(btrfs.writeFile(liveSubvolume, relative, captured)) { "VFS write rejected: $path" }
        openFiles[relative]?.get()?.bytes = captured
        generation.incrementAndGet()
        Unit
    }

    fun put(files: Series<Join<String, ByteArray>>): Unit = synchronized(fileLock) {
        val relativeFiles = ArrayList<Join<String, ByteArray>>(files.size)
        for ((path, bytes) in files.view) {
            val relative = relativeOf(parsePath(path))
            require(relative.isNotEmpty()) { "cannot write VFS root" }
            relativeFiles += relative j bytes.copyOf()
        }
        check(btrfs.writeFiles(liveSubvolume, relativeFiles.toSeries())) { "VFS batch write rejected" }
        for ((path, bytes) in relativeFiles) openFiles[path]?.get()?.bytes = bytes
        generation.addAndGet(relativeFiles.size.toLong())
        Unit
    }

    fun fetch(path: String): ByteArray? = synchronized(fileLock) { btrfs.fetchFile(liveSubvolume, relativeOf(parsePath(path))) }

    /** Atomically compare and publish through this mount; other mounts require separate ownership. */
    fun putIf(path: String, expected: ByteArray?, bytes: ByteArray): Boolean = synchronized(fileLock) {
        val current = btrfs.fetchFile(liveSubvolume, relativeOf(parsePath(path)))
        if (if (expected == null) current != null else current == null || !expected.contentEquals(current)) return@synchronized false
        put(path, bytes)
        true
    }

    fun snapshot(name: String): Boolean = synchronized(fileLock) { btrfs.snapshot(liveSubvolume, name) }

    fun fetchSnapshot(name: String, path: String): ByteArray? =
        synchronized(fileLock) { btrfs.fetchFile(name, relativeOf(parsePath(path))) }

    fun generation(): Long = generation.get()

    /** POSIX toolbox seam; callers using the raw mount must exclude concurrent VFS operations. */
    fun btrfsForTools(): UserspaceBtrfs = btrfs
    fun liveSubvolumeForTools(): String = liveSubvolume

    override fun parsePath(uri: URI): Path {
        require(uri.scheme == null || uri.scheme == "file") { "unsupported VFS URI scheme: ${uri.scheme}" }
        return Path.of(uri.path ?: "/")
    }

    override fun parsePath(path: String): Path = Path.of(path.ifEmpty { "." })

    override fun checkAccess(path: Path, modes: Set<out AccessMode>, vararg linkOptions: LinkOption) = synchronized(fileLock) {
        val relative = relativeOf(path)
        if (!exists(relative)) throw NoSuchFileException(toAbsolutePath(path).toString())
        if (AccessMode.EXECUTE in modes && !isDirectory(relative)) {
            throw java.nio.file.AccessDeniedException(toAbsolutePath(path).toString(), null, "VFS files are not executable")
        }
    }

    override fun createDirectory(path: Path, vararg attrs: FileAttribute<*>): Unit = synchronized(fileLock) {
        val relative = relativeOf(path)
        if (relative.isEmpty() || exists(relative)) throw FileAlreadyExistsException(toAbsolutePath(path).toString())
        if (!btrfs.createDirectory(liveSubvolume, relative)) throw IOException("cannot create VFS directory: $path")
        generation.incrementAndGet()
        Unit
    }

    override fun delete(path: Path) = synchronized(fileLock) {
        val relative = relativeOf(path)
        if (relative.isEmpty()) throw java.nio.file.AccessDeniedException("/")
        if (!exists(relative)) throw NoSuchFileException(path.toString())
        if (!btrfs.deleteFile(liveSubvolume, relative)) throw IOException("cannot delete VFS path: $path")
        openFiles.remove(relative)
        generation.incrementAndGet()
        Unit
    }

    override fun newByteChannel(
        path: Path,
        options: Set<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel = synchronized(fileLock) {
        val relative = relativeOf(path)
        if (relative == "dev/null") return@synchronized DevNullByteChannel
        if (relative.isEmpty() || isDirectory(relative)) throw FileSystemException(path.toString(), null, "is a directory")
        val write = StandardOpenOption.WRITE in options || StandardOpenOption.APPEND in options
        val read = StandardOpenOption.READ in options || !write
        require(StandardOpenOption.APPEND !in options || (!read && StandardOpenOption.TRUNCATE_EXISTING !in options)) {
            "APPEND cannot be combined with READ or TRUNCATE_EXISTING"
        }
        val create = write && (StandardOpenOption.CREATE in options || StandardOpenOption.CREATE_NEW in options)
        val exists = btrfs.isFile(liveSubvolume, relative)
        if (write && StandardOpenOption.CREATE_NEW in options && exists) throw FileAlreadyExistsException(path.toString())
        if (!exists && !create) throw NoSuchFileException(path.toString())
        val truncate = write && StandardOpenOption.TRUNCATE_EXISTING in options
        val initial = if (exists && !truncate) btrfs.fetchFile(liveSubvolume, relative)
            ?: throw IOException("missing or corrupt VFS file: $path") else ByteArray(0)
        // open(2) visibility: O_CREAT materializes the entry and O_TRUNC empties it NOW, not at close.
        if (write && (!exists || truncate)) {
            if (!btrfs.writeFile(liveSubvolume, relative, ByteArray(0))) throw IOException("VFS commit rejected: $path")
            generation.incrementAndGet()
        }
        openFiles.entries.removeAll { it.value.get() == null }
        var file = openFiles[relative]?.get()
        if (file == null) {
            lateinit var created: VfsFile
            created = VfsFile(initial, { check(btrfs.hasSubvolume(liveSubvolume)) { "VFS subvolume is unavailable" } }) { bytes ->
                if (openFiles[relative]?.get() === created) {
                    if (!btrfs.writeFile(liveSubvolume, relative, bytes)) throw IOException("VFS commit rejected: $path")
                    generation.incrementAndGet()
                }
            }
            openFiles[relative] = WeakReference(created)
            file = created
        } else if (truncate) file.bytes = ByteArray(0)
        VfsByteChannel(
            file = file,
            readable = read,
            writable = write,
            append = StandardOpenOption.APPEND in options,
            lock = fileLock,
        )
    }

    override fun newDirectoryStream(path: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = synchronized(fileLock) {
        val relative = relativeOf(path)
        val children = btrfs.listDirectory(liveSubvolume, relative) ?: throw java.nio.file.NotDirectoryException(path.toString())
        val parent = toAbsolutePath(path)
        val maskedChildren = if (relative == "workspace" || relative == "tmp") {
            children.map { childName ->
                val childRelative = "$relative/$childName"
                val isDir = btrfs.isDirectory(liveSubvolume, childRelative)
                canonicalizer.canonicalizePath(childName, isDir)
            }
        } else children
        object : DirectoryStream<Path> {
            private var open = true
            override fun iterator(): MutableIterator<Path> {
                check(open) { "directory stream closed" }
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

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path = synchronized(fileLock) {
        val absolute = toAbsolutePath(path)
        if (!exists(relativeOf(absolute))) throw NoSuchFileException(absolute.toString())
        absolute
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> = synchronized(fileLock) {
        val relative = relativeOf(path)
        if (!exists(relative)) throw NoSuchFileException(path.toString())
        val directory = isDirectory(relative)
        val size = if (directory) 0L else btrfs.fetchFile(liveSubvolume, relative)?.size?.toLong()
            ?: throw IOException("missing or corrupt VFS file: $path")
        val times = btrfs.times(liveSubvolume, relative) ?: throw NoSuchFileException(path.toString())
        fun time(value: kotlin.time.Instant): FileTime =
            FileTime.from(java.time.Instant.ofEpochSecond(value.epochSeconds, value.nanosecondsOfSecond.toLong()))
        val inode = inodeOf(relative)
        val all = linkedMapOf<String, Any>(
            "isRegularFile" to !directory,
            "isDirectory" to directory,
            "isSymbolicLink" to false,
            "isOther" to false,
            "size" to size,
            "fileKey" to inode,
            "lastModifiedTime" to time(times.lastModifiedTime),
            "lastAccessTime" to time(times.lastAccessTime),
            "creationTime" to time(times.creationTime),
            "mode" to if (directory) 16877 else 33188, // 040755 | 0100644
            "ino" to inode,
            "dev" to 0L,
            "nlink" to 1,
            "uid" to 0,
            "gid" to 0,
            "ctime" to time(times.changeTime),
        )
        val requested = attributes.substringAfter(':')
        if (requested == "*" || requested.isBlank()) all
        else {
            val names = requested.split(',').toSet()
            all.filterKeys { it in names }
        }
    }

    override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption): Unit = synchronized(fileLock) {
        val view = attribute.substringBefore(':', "basic")
        val name = attribute.substringAfter(':')
        if (view !in setOf("basic", "unix") || name !in setOf("lastModifiedTime", "lastAccessTime", "creationTime"))
            throw UnsupportedOperationException("unsupported VFS attribute: $attribute")
        val time = (value as? FileTime ?: throw IllegalArgumentException("$attribute requires FileTime")).toInstant()
        val instant = kotlin.time.Instant.fromEpochSeconds(time.epochSecond, time.nano).also {
            require(it.epochSeconds == time.epochSecond && it.nanosecondsOfSecond == time.nano) { "VFS time overflow" }
        }
        val relative = relativeOf(path)
        if (!exists(relative)) throw NoSuchFileException(path.toString())
        if (!btrfs.setTimes(liveSubvolume, relative,
                lastModifiedTime = instant.takeIf { name == "lastModifiedTime" },
                lastAccessTime = instant.takeIf { name == "lastAccessTime" },
                creationTime = instant.takeIf { name == "creationTime" }))
            throw FileSystemException(path.toString(), null, "VFS timestamp commit rejected")
        generation.incrementAndGet()
        Unit
    }

    override fun setCurrentWorkingDirectory(currentWorkingDirectory: Path) = synchronized(fileLock) {
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

/**
 * Channels for the same open file share its bytes and commit each mutation (write(2)/ftruncate(2)
 * visibility): GraalPy has no refcounted close, so a guest writer that is never close()d flushes at
 * no deterministic point — commit-on-close alone silently lost such writes (the guest-write bug).
 * An unlinked open file remains accessible through its handles, but no longer publishes to its old path.
 */
private class VfsFile(var bytes: ByteArray, val checkMount: () -> Unit, val commit: (ByteArray) -> Unit)

private class VfsByteChannel(
    file: VfsFile,
    private val readable: Boolean,
    private val writable: Boolean,
    private val append: Boolean,
    private val lock: Any,
) : SeekableByteChannel {
    private var file: VfsFile? = file
    private var cursor = if (append) file.bytes.size else 0

    override fun isOpen(): Boolean = synchronized(lock) { file != null }

    override fun close() = synchronized(lock) { file = null }

    override fun read(dst: ByteBuffer): Int = synchronized(lock) {
        val data = ensureOpen().bytes
        if (!readable) throw NonReadableChannelException()
        if (!dst.hasRemaining()) return@synchronized 0
        if (cursor >= data.size) return@synchronized -1
        val count = minOf(dst.remaining(), data.size - cursor)
        dst.put(data, cursor, count)
        cursor += count
        count
    }

    override fun write(src: ByteBuffer): Int = synchronized(lock) {
        val file = ensureOpen()
        val data = file.bytes
        if (!writable) throw NonWritableChannelException()
        val count = src.remaining()
        if (count == 0) return@synchronized 0
        val offset = if (append) data.size else cursor
        require(count <= Int.MAX_VALUE - offset) { "VFS file exceeds byte-array capacity" }
        val end = offset + count
        val candidate = data.copyOf(maxOf(data.size, end))
        src.duplicate().get(candidate, offset, count)
        file.commit(candidate)
        file.bytes = candidate
        cursor = end
        src.position(src.position() + count)
        count
    }

    override fun position(): Long = synchronized(lock) { ensureOpen(); cursor.toLong() }

    override fun position(newPosition: Long): SeekableByteChannel = synchronized(lock) {
        ensureOpen()
        require(newPosition in 0..Int.MAX_VALUE.toLong()) { "invalid position: $newPosition" }
        cursor = newPosition.toInt()
        this
    }

    override fun size(): Long = synchronized(lock) { ensureOpen().bytes.size.toLong() }

    override fun truncate(size: Long): SeekableByteChannel = synchronized(lock) {
        val file = ensureOpen()
        if (!writable) throw NonWritableChannelException()
        require(size in 0..Int.MAX_VALUE.toLong()) { "invalid size: $size" }
        if (size < file.bytes.size) {
            val candidate = file.bytes.copyOf(size.toInt())
            file.commit(candidate)
            file.bytes = candidate
        }
        if (cursor > size) cursor = size.toInt()
        this
    }

    private fun ensureOpen(): VfsFile = (file ?: throw ClosedChannelException()).also { it.checkMount() }

}

/**
 * Supervisor-owned Graal guest with an isolated, snapshot-capable UserspaceBtrfs VFS.
 *
 * [world] decides whether that VFS is file-based. The default is [BtrfsWorldStore.ofMemory], which
 * is what this class always did implicitly by taking `TrikeShedGraalVfs`'s in-memory default
 * argument — worth naming rather than inheriting, because a world that cannot outlive its process
 * is a different product from one that can. Hand it [BtrfsWorldStore.ofFiles] and the guest's
 * subvolume, its extents and its snapshots are on disk and readable by the next boot.
 */
class GraalBtrfsSupervisor(
    override val id: String,
    override val facet: VmFacet,
    budget: Budget = Budget(),
    input: InputStream? = null,
    output: OutputStream? = null,
    error: OutputStream? = output,
    world: BtrfsWorldStore = BtrfsWorldStore.ofMemory(),
    onRootReturn: (RootObservation) -> Unit = {},
) : GuestIsolate {
    val vfs = TrikeShedGraalVfs(
        fileOps = world.fileOpsFor(id),
        btrfsRoot = world.root,
        // One subvolume per guest: on a shared file root the guests would otherwise all be "live"
        // and see each other's files. The isolation this class advertises is the subvolume.
        liveSubvolume = world.subvolumeFor(id),
        instanceId = id,
        cas = world.cas,
    )
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
    fun put(files: Series<Join<String, ByteArray>>) = vfs.put(files)
    fun snapshot(name: String): Boolean = vfs.snapshot(name)
    override val trust: Trust get() = Trust.OWN
    override val bounds: FacetBounds get() = guest.bounds
    override fun eval(source: String, name: String): Teleported = guest.eval(source, name)
    override fun call(root: String, vararg args: Teleported): Teleported = guest.call(root, *args)
    override fun delegate(name: String, fn: (List<Teleported>) -> Teleported) = guest.delegate(name, fn)
    override fun delegate(name: String, hostWallMillis: Long, fn: (List<Teleported>) -> Teleported) =
        guest.delegate(name, hostWallMillis, fn)
    override fun interrupt(): Boolean = guest.interrupt()
    override fun stats(): IsolateStats = guest.stats()
    override val isAlive: Boolean get() = guest.isAlive
    override fun close() = guest.close()
    fun quiesce(finalize: () -> Unit = {}) = guest.quiesce(finalize)
    fun closeAndAwait() = guest.closeAndAwait()
}
