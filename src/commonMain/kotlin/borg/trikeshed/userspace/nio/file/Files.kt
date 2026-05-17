package borg.trikeshed.userspace.nio.file

import borg.trikeshed.userspace.ChannelsImpl
import borg.trikeshed.userspace.FileImpl
import borg.trikeshed.userspace.FilesImpl
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.SeekableByteChannel
import borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes
import borg.trikeshed.userspace.nio.file.attribute.FileAttribute
import borg.trikeshed.userspace.nio.file.attribute.FileAttributeView
import borg.trikeshed.userspace.nio.file.attribute.FileTime
import borg.trikeshed.userspace.nio.file.attribute.PosixFilePermission
import borg.trikeshed.userspace.nio.file.attribute.UserPrincipal
import borg.trikeshed.userspace.nio.charset.Charset
import borg.trikeshed.userspace.nio.charset.StandardCharsets

/**
 * NIO Files facade — delegates to expect/actual [FilesImpl].
 *
 * Mirrors java.nio.file.Files API.
 * For async operations, use [FileOperations] SPI via CoroutineContext.
 */
public object Files {
    // ── Factory ──────────────────────────────────────────────────────────────

    public fun open(path: String, readOnly: Boolean = true): File {
        val impl = FilesImpl.open(path, readOnly)
        return File(impl)
    }

    // ByteChannel factories
    public fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
        FileChannel.open(path, options, *attrs)
    public fun newByteChannel(path: Path, vararg options: OpenOption): SeekableByteChannel =
        FileChannel.open(path, *options)

    // Read operations
    public fun readAllBytes(path: Path): ByteArray {
        val ch = FileChannel.open(path, StandardOpenOption.READ)
        val sz = size(path).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = ByteArray(sz)
        val buf = ByteBuffer(bytes)
        ch.use { it.read(buf) }
        return bytes
    }

    public fun readString(path: Path, charset: Charset = StandardCharsets.UTF_8): String =
        readAllBytes(path).decodeToString()

    public fun readAllLines(path: Path, charset: Charset = StandardCharsets.UTF_8): List<String> =
        readString(path, charset).split('\n')
    public fun readAllLines(path: Path): List<String> = readAllLines(path, StandardCharsets.UTF_8)

    // Write operations
    public fun write(path: Path, bytes: ByteArray, vararg options: OpenOption): Path {
        val ch = FileChannel.open(path, *options)
        ch.use { it.write(ByteBuffer(bytes)) }
        return path
    }

    public fun write(path: Path, lines: Iterable<CharSequence>, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Path {
        val bytes: ByteArray = lines.joinToString("\n").encodeToByteArray()
        return write(path, bytes, *options)
    }
    public fun write(path: Path, lines: Iterable<CharSequence>, vararg options: OpenOption): Path =
        write(path, lines, StandardCharsets.UTF_8, *options)

    public fun writeString(path: Path, csq: CharSequence, vararg options: OpenOption): Path =
        write(path, csq.toString().encodeToByteArray(), *options)
    public fun writeString(path: Path, csq: CharSequence, charset: Charset, vararg options: OpenOption): Path {
        val bytes: ByteArray = csq.toString().encodeToByteArray()
        return write(path, bytes, *options)
    }

    // Attribute queries
    public fun exists(path: Path, vararg options: LinkOption): Boolean =
        FilesImpl.let { facade ->
            try { facade.open(path.toString(), readOnly = true); true }
            catch (_: Exception) { false }
        }

    public fun notExists(path: Path, vararg options: LinkOption): Boolean = !exists(path, *options)

    public fun size(path: Path): Long {
        val ch = FileChannel.open(path, StandardOpenOption.READ)
        return ch.use { it.size() }
    }

    public fun isDirectory(path: Path, vararg options: LinkOption): Boolean = TODO("fstat S_ISDIR")
    public fun isRegularFile(path: Path, vararg options: LinkOption): Boolean = TODO("fstat S_ISREG")
    public fun isHidden(path: Path): Boolean = path.toString().startsWith(".")
    public fun isReadable(path: Path): Boolean = exists(path)
    public fun isWritable(path: Path): Boolean = exists(path)
    public fun isExecutable(path: Path): Boolean = false

    public fun getLastModifiedTime(path: Path, vararg options: LinkOption): FileTime = TODO("fstat mtime")
    public fun setLastModifiedTime(path: Path, time: FileTime): Path = TODO("utimes")
    public fun getOwner(path: Path, vararg options: LinkOption): UserPrincipal = TODO("stat uid")
    public fun setOwner(path: Path, owner: UserPrincipal): Path = TODO("chown")
    public fun isSymbolicLink(path: Path): Boolean = TODO("lstat")
    public fun isSameFile(path: Path, other: Path): Boolean = TODO("stat dev+ino")
    public fun mismatch(path: Path, other: Path): Long = TODO("memcmp")

    // Directory operations
    public fun createDirectory(path: Path, vararg attrs: FileAttribute<*>): Path = TODO("mkdir")
    public fun createDirectories(path: Path, vararg attrs: FileAttribute<*>): Path = TODO("mkdir -p")
    public fun createFile(path: Path, vararg attrs: FileAttribute<*>): Path = TODO("creat")
    public fun createTempFile(dir: Path?, prefix: String?, suffix: String?, vararg attrs: FileAttribute<*>): Path = TODO("mkstemp")
    public fun createTempFile(prefix: String?, suffix: String?, vararg attrs: FileAttribute<*>): Path = createTempFile(null, prefix, suffix, *attrs)
    public fun createTempDirectory(dir: Path?, prefix: String?, vararg attrs: FileAttribute<*>): Path = TODO("mkdtemp")
    public fun createTempDirectory(prefix: String?, vararg attrs: FileAttribute<*>): Path = createTempDirectory(null, prefix, *attrs)
    public fun delete(path: Path): Unit { deleteIfExists(path) }
    public fun deleteIfExists(path: Path): Boolean = TODO("unlink")

    public fun list(path: Path): Sequence<Path> = TODO("opendir/readdir")
    public fun newDirectoryStream(path: Path): DirectoryStream<Path> = TODO("opendir")
    public fun newDirectoryStream(path: Path, glob: String): DirectoryStream<Path> = TODO("opendir + glob")
    public fun newDirectoryStream(path: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = TODO("opendir + filter")

    public fun lines(path: Path, charset: Charset = StandardCharsets.UTF_8): Sequence<String> = readString(path, charset).lineSequence()
    public fun lines(path: Path): Sequence<String> = lines(path, StandardCharsets.UTF_8)

    public fun walk(path: Path, maxDepth: Int, vararg options: FileVisitOption): Sequence<Path> = TODO("walk")
    public fun walk(path: Path, vararg options: FileVisitOption): Sequence<Path> = walk(path, Int.MAX_VALUE, *options)
    public fun walkFileTree(path: Path, options: Set<FileVisitOption>, maxDepth: Int, visitor: FileVisitor<in Path>): Path = TODO("walkFileTree")
    public fun walkFileTree(path: Path, visitor: FileVisitor<in Path>): Path = TODO("walkFileTree")
    public fun find(path: Path, maxDepth: Int, matcher: (Path, BasicFileAttributes) -> Boolean, vararg options: FileVisitOption): Sequence<Path> = TODO("find")

    // Copy/move/link
    public fun copy(src: Path, dst: Path, vararg options: CopyOption): Path = TODO("copy")
    public fun move(src: Path, dst: Path, vararg options: CopyOption): Path = TODO("rename")
    public fun createLink(link: Path, existing: Path): Path = TODO("link")
    public fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>): Path = TODO("symlink")
    public fun readSymbolicLink(link: Path): Path = TODO("readlink")

    // Attributes
    public fun <A : BasicFileAttributes> readAttributes(path: Path, type: kotlin.reflect.KClass<A>, vararg options: LinkOption): A = TODO("stat")
    public fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> = TODO("stat")
    public fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption): Path = TODO("setxattr")
    public fun getAttribute(path: Path, attribute: String, vararg options: LinkOption): Any = TODO("getxattr")
    public fun getPosixFilePermissions(path: Path, vararg options: LinkOption): Set<PosixFilePermission> = TODO("stat mode")
    public fun setPosixFilePermissions(path: Path, perms: Set<PosixFilePermission>): Path = TODO("chmod")
    public fun <V : FileAttributeView> getFileAttributeView(path: Path, type: kotlin.reflect.KClass<V>, vararg options: LinkOption): V = TODO("attribute view")

    // Streams
    public fun newInputStream(path: Path, vararg options: OpenOption): Any = TODO("InputStream")
    public fun newOutputStream(path: Path, vararg options: OpenOption): Any = TODO("OutputStream")
    public fun newBufferedReader(path: Path, charset: Charset = StandardCharsets.UTF_8): Any = TODO("BufferedReader")
    public fun newBufferedWriter(path: Path, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Any = TODO("BufferedWriter")
    public fun copy(`in`: Any, path: Path, vararg options: CopyOption): Long = TODO("copy stream->file")
    public fun copy(path: Path, out: Any): Long = TODO("copy file->stream")
    public fun getFileStore(path: Path): FileStore = TODO("statfs")
    public fun probeContentType(path: Path): String = TODO("content type")
}