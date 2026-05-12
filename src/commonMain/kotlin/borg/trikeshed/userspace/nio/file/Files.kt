@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.file

import borg.trikeshed.lib.Join
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.SeekableByteChannel
import borg.trikeshed.userspace.nio.charset.Charset
import borg.trikeshed.userspace.nio.charset.StandardCharsets
import borg.trikeshed.userspace.nio.file.attribute.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations

public class Files {
    companion object {
        // --- String-path overloads (delegate to FileOperations) ---

        fun newByteChannel(path: String, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
            FileChannel.open(Paths.get(path), options, *attrs)
        fun newByteChannel(path: String, vararg options: OpenOption): SeekableByteChannel =
            FileChannel.open(Paths.get(path), *options)

        fun readAllBytes(path: String): ByteArray = fileOperations.readAllBytes(path)
        fun readString(path: String, charset: Charset = StandardCharsets.UTF_8): String = fileOperations.readString(path)
        fun readAllLines(path: String, charset: Charset = StandardCharsets.UTF_8): List<String> = fileOperations.readAllLines(path)
        fun readAllLines(path: String): List<String> = readAllLines(path, StandardCharsets.UTF_8)

        fun write(path: String, bytes: ByteArray, vararg options: OpenOption): Path {
            fileOperations.write(path, bytes)
            return Paths.get(path)
        }
        fun write(path: String, lines: Iterable<CharSequence>, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Path =
            write(path, lines.joinToString("\n").encodeToByteArray(), *options)
        fun write(path: String, lines: Iterable<CharSequence>, vararg options: OpenOption): Path =
            write(path, lines, StandardCharsets.UTF_8, *options)
        fun writeString(path: String, csq: CharSequence, vararg options: OpenOption): Path =
            write(path, csq.toString().encodeToByteArray(), *options)
        fun writeString(path: String, csq: CharSequence, charset: Charset, vararg options: OpenOption): Path {
            fileOperations.write(path, csq.toString())
            return Paths.get(path)
        }
        fun write(path: String, string: String): Path {
            fileOperations.write(path, string)
            return Paths.get(path)
        }

        fun exists(path: String, vararg options: LinkOption): Boolean = fileOperations.exists(path)
        fun notExists(path: String, vararg options: LinkOption): Boolean = !exists(path, *options)
        fun size(path: String): Long {
            val ch = FileChannel.open(Paths.get(path), StandardOpenOption.READ)
            return ch.use { it.size() }
        }
        fun isDirectory(path: String, vararg options: LinkOption): Boolean = fileOperations.isDir(path)
        fun isRegularFile(path: String, vararg options: LinkOption): Boolean = fileOperations.isFile(path)
        fun isHidden(path: String): Boolean = path.startsWith(".")
        fun isReadable(path: String): Boolean = exists(path)
        fun isWritable(path: String): Boolean = exists(path)
        fun isExecutable(path: String): Boolean = false
        fun getLastModifiedTime(path: String, vararg options: LinkOption): FileTime = TODO("fstat mtime")
        fun setLastModifiedTime(path: String, time: FileTime): Path = TODO("utimes")
        fun getOwner(path: String, vararg options: LinkOption): UserPrincipal = TODO("stat uid")
        fun setOwner(path: String, owner: UserPrincipal): Path = TODO("chown")
        fun isSymbolicLink(path: String): Boolean = TODO("lstat")
        fun isSameFile(path: String, other: String): Boolean = TODO("stat dev+ino")
        fun mismatch(path: String, other: String): Long = TODO("memcmp")

        fun createDirectory(path: String, vararg attrs: FileAttribute<*>): Path {
            fileOperations.mkdirs(path)
            return Paths.get(path)
        }
        fun createDirectories(path: String, vararg attrs: FileAttribute<*>): Path {
            fileOperations.mkdirs(path)
            return Paths.get(path)
        }
        fun createFile(path: String, vararg attrs: FileAttribute<*>): Path = TODO("creat")
        fun createTempFile(dir: String?, prefix: String?, suffix: String?, vararg attrs: FileAttribute<*>): Path = TODO("mkstemp")
        fun createTempDirectory(dir: String?, prefix: String?, vararg attrs: FileAttribute<*>): Path =
            Paths.get(fileOperations.createTempDir(prefix ?: "tmp"))

        fun delete(path: String) { deleteIfExists(path) }
        fun deleteIfExists(path: String): Boolean {
            if (!exists(path)) return false
            fileOperations.deleteRecursively(path)
            return true
        }
        fun deleteRecursively(path: String) { fileOperations.deleteRecursively(path) }
        fun list(path: String): Sequence<Path> =
            fileOperations.listDir(path).map { Paths.get(it) }.asSequence()
        fun newDirectoryStream(path: String): DirectoryStream<Path> = TODO("opendir")
        fun newDirectoryStream(path: String, glob: String): DirectoryStream<Path> = TODO("opendir + glob")
        fun newDirectoryStream(path: String, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = TODO("opendir + filter")
        fun lines(path: String, charset: Charset = StandardCharsets.UTF_8): Sequence<String> = readString(path, charset).lineSequence()
        fun lines(path: String): Sequence<String> = lines(path, StandardCharsets.UTF_8)

        /**
         * Returns each line as a pair of (byteOffset, lineBytes).
         * Uses [streamByteLines] which splits on `\n`, preserving the offset of each line
         * in the original file — useful for seek+read cross-validation.
         */
        fun fragments(path: String): Sequence<Join<Long, ByteArray>> =
            borg.trikeshed.lib.streamByteLines(readAllBytes(path))
        fun walk(path: String, maxDepth: Int, vararg options: FileVisitOption): Sequence<Path> = TODO("walk")
        fun walk(path: String, vararg options: FileVisitOption): Sequence<Path> = walk(path, Int.MAX_VALUE, *options)
        fun walkFileTree(path: String, options: Set<FileVisitOption>, maxDepth: Int, visitor: FileVisitor<in Path>): Path = TODO("walkFileTree")
        fun walkFileTree(path: String, visitor: FileVisitor<in Path>): Path = TODO("walkFileTree")
        fun find(path: String, maxDepth: Int, matcher: (Path, BasicFileAttributes) -> Boolean, vararg options: FileVisitOption): Sequence<Path> = TODO("find")
        fun copy(src: String, dst: String, vararg options: CopyOption): Path = TODO("copy")
        fun move(src: String, dst: String, vararg options: CopyOption): Path = TODO("rename")
        fun createLink(link: String, existing: String): Path = TODO("link")
        fun createSymbolicLink(link: String, target: String, vararg attrs: FileAttribute<*>): Path = TODO("symlink")
        fun readSymbolicLink(link: String): Path = TODO("readlink")
        fun <A : BasicFileAttributes> readAttributes(path: String, type: kotlin.reflect.KClass<A>, vararg options: LinkOption): A = TODO("stat")
        fun readAttributes(path: String, attributes: String, vararg options: LinkOption): Map<String, Any> = TODO("stat")
        fun setAttribute(path: String, attribute: String, value: Any, vararg options: LinkOption): Path = TODO("setxattr")
        fun getAttribute(path: String, attribute: String, vararg options: LinkOption): Any = TODO("getxattr")
        fun getPosixFilePermissions(path: String, vararg options: LinkOption): Set<PosixFilePermission> = TODO("stat mode")
        fun setPosixFilePermissions(path: String, perms: Set<PosixFilePermission>): Path = TODO("chmod")
        fun <V : FileAttributeView> getFileAttributeView(path: String, type: kotlin.reflect.KClass<V>, vararg options: LinkOption): V = TODO("attribute view")
        fun newInputStream(path: String, vararg options: OpenOption): Any = TODO("InputStream")
        fun newOutputStream(path: String, vararg options: OpenOption): Any = TODO("OutputStream")
        fun newBufferedReader(path: String, charset: Charset = StandardCharsets.UTF_8): Any = TODO("BufferedReader")
        fun newBufferedWriter(path: String, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Any = TODO("BufferedWriter")
        fun copy(`in`: Any, path: String, vararg options: CopyOption): Long = TODO("copy stream->file")
        fun copy(path: String, out: Any): Long = TODO("copy file->stream")
        fun getFileStore(path: String): FileStore = TODO("statfs")
        fun probeContentType(path: String): String = TODO("content type")
 fun cwd(): String = fileOperations.cwd()

        // --- Path-based overloads (delegate to String-path versions) ---

        fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
            newByteChannel(path.toString(), options, *attrs)
        fun newByteChannel(path: Path, vararg options: OpenOption): SeekableByteChannel =
            newByteChannel(path.toString(), *options)
        fun readAllBytes(path: Path): ByteArray = readAllBytes(path.toString())
        fun readString(path: Path, charset: Charset = StandardCharsets.UTF_8): String = readString(path.toString(), charset)
        fun readAllLines(path: Path, charset: Charset = StandardCharsets.UTF_8): List<String> = readAllLines(path.toString(), charset)
        fun readAllLines(path: Path): List<String> = readAllLines(path.toString())
        fun write(path: Path, bytes: ByteArray, vararg options: OpenOption): Path = write(path.toString(), bytes, *options)
        fun write(path: Path, lines: Iterable<CharSequence>, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Path = write(path.toString(), lines, charset, *options)
        fun write(path: Path, lines: Iterable<CharSequence>, vararg options: OpenOption): Path = write(path.toString(), lines, *options)
        fun writeString(path: Path, csq: CharSequence, vararg options: OpenOption): Path = writeString(path.toString(), csq, *options)
        fun writeString(path: Path, csq: CharSequence, charset: Charset, vararg options: OpenOption): Path = writeString(path.toString(), csq, charset, *options)
        fun exists(path: Path, vararg options: LinkOption): Boolean = exists(path.toString(), *options)
        fun notExists(path: Path, vararg options: LinkOption): Boolean = notExists(path.toString(), *options)
        fun size(path: Path): Long = size(path.toString())
        fun isDirectory(path: Path, vararg options: LinkOption): Boolean = isDirectory(path.toString(), *options)
        fun isRegularFile(path: Path, vararg options: LinkOption): Boolean = isRegularFile(path.toString(), *options)
        fun isHidden(path: Path): Boolean = isHidden(path.toString())
        fun isReadable(path: Path): Boolean = isReadable(path.toString())
        fun isWritable(path: Path): Boolean = isWritable(path.toString())
        fun isExecutable(path: Path): Boolean = isExecutable(path.toString())
        fun getLastModifiedTime(path: Path, vararg options: LinkOption): FileTime = getLastModifiedTime(path.toString(), *options)
        fun setLastModifiedTime(path: Path, time: FileTime): Path = setLastModifiedTime(path.toString(), time)
        fun getOwner(path: Path, vararg options: LinkOption): UserPrincipal = getOwner(path.toString(), *options)
        fun setOwner(path: Path, owner: UserPrincipal): Path = setOwner(path.toString(), owner)
        fun isSymbolicLink(path: Path): Boolean = isSymbolicLink(path.toString())
        fun isSameFile(path: Path, other: Path): Boolean = isSameFile(path.toString(), other.toString())
        fun mismatch(path: Path, other: Path): Long = mismatch(path.toString(), other.toString())
        fun createDirectory(path: Path, vararg attrs: FileAttribute<*>): Path = createDirectory(path.toString(), *attrs)
        fun createDirectories(path: Path, vararg attrs: FileAttribute<*>): Path = createDirectories(path.toString(), *attrs)
        fun createFile(path: Path, vararg attrs: FileAttribute<*>): Path = createFile(path.toString(), *attrs)
        fun createTempFile(dir: Path?, prefix: String?, suffix: String?, vararg attrs: FileAttribute<*>): Path = createTempFile(dir?.toString(), prefix, suffix, *attrs)
        fun createTempDirectory(dir: Path?, prefix: String?, vararg attrs: FileAttribute<*>): Path = createTempDirectory(dir?.toString(), prefix, *attrs)
        fun delete(path: Path): Unit = delete(path.toString())
        fun deleteIfExists(path: Path): Boolean = deleteIfExists(path.toString())
        fun list(path: Path): Sequence<Path> = list(path.toString())
        fun newDirectoryStream(path: Path): DirectoryStream<Path> = newDirectoryStream(path.toString())
        fun newDirectoryStream(path: Path, glob: String): DirectoryStream<Path> = newDirectoryStream(path.toString(), glob)
        fun newDirectoryStream(path: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = newDirectoryStream(path.toString(), filter)
        fun lines(path: Path, charset: Charset = StandardCharsets.UTF_8): Sequence<String> = lines(path.toString(), charset)
        fun lines(path: Path): Sequence<String> = lines(path.toString())
        fun walk(path: Path, maxDepth: Int, vararg options: FileVisitOption): Sequence<Path> = walk(path.toString(), maxDepth, *options)
        fun walk(path: Path, vararg options: FileVisitOption): Sequence<Path> = walk(path.toString(), *options)
        fun walkFileTree(path: Path, options: Set<FileVisitOption>, maxDepth: Int, visitor: FileVisitor<in Path>): Path = walkFileTree(path.toString(), options, maxDepth, visitor)
        fun walkFileTree(path: Path, visitor: FileVisitor<in Path>): Path = walkFileTree(path.toString(), visitor)
        fun find(path: Path, maxDepth: Int, matcher: (Path, BasicFileAttributes) -> Boolean, vararg options: FileVisitOption): Sequence<Path> = find(path.toString(), maxDepth, matcher, *options)
        fun copy(src: Path, dst: Path, vararg options: CopyOption): Path = copy(src.toString(), dst.toString(), *options)
        fun move(src: Path, dst: Path, vararg options: CopyOption): Path = move(src.toString(), dst.toString(), *options)
        fun createLink(link: Path, existing: Path): Path = createLink(link.toString(), existing.toString())
        fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>): Path = createSymbolicLink(link.toString(), target.toString(), *attrs)
        fun readSymbolicLink(link: Path): Path = readSymbolicLink(link.toString())
        fun <A : BasicFileAttributes> readAttributes(path: Path, type: kotlin.reflect.KClass<A>, vararg options: LinkOption): A = readAttributes(path.toString(), type, *options)
        fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> = readAttributes(path.toString(), attributes, *options)
        fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption): Path = setAttribute(path.toString(), attribute, value, *options)
        fun getAttribute(path: Path, attribute: String, vararg options: LinkOption): Any = getAttribute(path.toString(), attribute, *options)
        fun getPosixFilePermissions(path: Path, vararg options: LinkOption): Set<PosixFilePermission> = getPosixFilePermissions(path.toString(), *options)
        fun setPosixFilePermissions(path: Path, perms: Set<PosixFilePermission>): Path = setPosixFilePermissions(path.toString(), perms)
        fun <V : FileAttributeView> getFileAttributeView(path: Path, type: kotlin.reflect.KClass<V>, vararg options: LinkOption): V = getFileAttributeView(path.toString(), type, *options)
        fun newInputStream(path: Path, vararg options: OpenOption): Any = newInputStream(path.toString(), *options)
        fun newOutputStream(path: Path, vararg options: OpenOption): Any = newOutputStream(path.toString(), *options)
        fun newBufferedReader(path: Path, charset: Charset = StandardCharsets.UTF_8): Any = newBufferedReader(path.toString(), charset)
        fun newBufferedWriter(path: Path, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Any = newBufferedWriter(path.toString(), charset, *options)
        fun copy(`in`: Any, path: Path, vararg options: CopyOption): Long = copy(`in`, path.toString(), *options)
        fun copy(path: Path, out: Any): Long = copy(path.toString(), out)
        fun getFileStore(path: Path): FileStore = getFileStore(path.toString())
        fun probeContentType(path: Path): String = probeContentType(path.toString())
    }
}

/*
*//**
 * Platform filesystem accessor.
 *
 * Resolves to the platform [borg.trikeshed.userspace.nio.file.spi.FileOperations] registered during init.
 * Usage unchanged: `Files.readString("foo.txt")`, `Files.cwd()`, etc.
 *
 * No expect/actual. Each platform's [PlatformProviders] assigns [fileOperations]
 * during static init. Until then, calls throw.
 *//*
val Files: FileOperations
    get() = fileOperations*/

/**
 * Mutable platform hook — each target's PlatformProviders sets this once during init.
 */
var fileOperations: FileOperations = UninitializedFileOperations
    internal set

private object UninitializedFileOperations : FileOperations {
    override val key get() = FileOperations.Key
    override fun readAllLines(filename: String): Nothing = error("FileOperations not initialized — call PlatformProviders.init() first")
    override fun readAllBytes(filename: String): Nothing = error("FileOperations not initialized — call PlatformProviders.init() first")
    override fun readString(filename: String): Nothing = error("FileOperations not initialized — call PlatformProviders.init() first")
    override fun write(filename: String, bytes: ByteArray): Nothing = error("FileOperations not initialized")
    override fun write(filename: String, lines: List<String>): Nothing = error("FileOperations not initialized")
    override fun write(filename: String, string: String): Nothing = error("FileOperations not initialized")
    override fun cwd(): Nothing = error("FileOperations not initialized")
    override fun exists(filename: String): Nothing = error("FileOperations not initialized")
    override fun streamLines(fileName: String, bufsize: Int): Nothing = error("FileOperations not initialized")
    override fun iterateLines(fileName: String, bufsize: Int): Nothing = error("FileOperations not initialized")
    override fun listDir(path: String): Nothing = error("FileOperations not initialized")
    override fun isDir(path: String): Boolean = error("FileOperations not initialized")
    override fun isFile(path: String): Boolean = error("FileOperations not initialized")
    override fun mkdirs(path: String): Nothing = error("FileOperations not initialized")
    override fun deleteRecursively(path: String): Nothing = error("FileOperations not initialized")
    override fun resolvePath(vararg parts: String): Nothing = error("FileOperations not initialized")
    override fun readZip(path: String): Nothing = error("FileOperations not initialized")
    override fun createTempDir(prefix: String): Nothing = error("FileOperations not initialized")
}