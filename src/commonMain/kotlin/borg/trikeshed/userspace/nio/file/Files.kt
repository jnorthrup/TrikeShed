@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.file

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.SeekableByteChannel
import borg.trikeshed.userspace.nio.charset.Charset
import borg.trikeshed.userspace.nio.charset.StandardCharsets
import borg.trikeshed.userspace.nio.file.attribute.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations

public class Files {
    companion object {
        // --- CharSequence-path overloads (delegate to FileOperations) ---

        fun newByteChannel(path: CharSequence, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
            FileChannel.open(Paths[path], options, *attrs)
        fun newByteChannel(path: CharSequence, vararg options: OpenOption): SeekableByteChannel =
            FileChannel.open(Paths[path], *options)

        fun readAllBytes(path: CharSequence): ByteArray = fileOperations.readAllBytes(path)
        fun readString(path: CharSequence, charset: Charset = StandardCharsets.UTF_8): CharSequence = fileOperations.readString(path)
        fun readAllLines(path: CharSequence, charset: Charset = StandardCharsets.UTF_8): Series<CharSequence> = fileOperations.readAllLines(path)
        fun readAllLines(path: CharSequence): Series<CharSequence> = readAllLines(path, StandardCharsets.UTF_8)

        fun write(path: CharSequence, bytes: ByteArray, vararg options: OpenOption): Path {
            fileOperations.write( path, bytes)
            return Paths[path]
        }
        fun write(path: CharSequence, lines: Iterable<CharSequence>, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Path {
            fileOperations.write(path, lines.toList().toSeries())
            return Paths[path]
        }
        fun write(path: CharSequence, lines: Iterable<CharSequence>, vararg options: OpenOption): Path =
            write(path, lines, StandardCharsets.UTF_8, *options)
        fun writeString(path: CharSequence, csq: CharSequence, vararg options: OpenOption): Path =
            write(path, csq.toString().encodeToByteArray(), *options)
        fun writeString(path: CharSequence, csq: CharSequence, charset: Charset, vararg options: OpenOption): Path {
            fileOperations.write(path, csq.toString())
            return Paths[path]
        }
        fun write(path: CharSequence, CharSequence: CharSequence): Path {
            fileOperations.write(path, CharSequence)
            return Paths[path]
        }

        fun exists(path: CharSequence, vararg options: LinkOption): Boolean = fileOperations.exists(path)
        fun notExists(path: CharSequence, vararg options: LinkOption): Boolean = !exists(path, *options)
        fun size(path: CharSequence): Long {
            val ch = FileChannel.open(Paths[path], StandardOpenOption.READ)
            return ch.use { it.size() }
        }
        fun isDirectory(path: CharSequence, vararg options: LinkOption): Boolean = fileOperations.isDir(path)
        fun isRegularFile(path: CharSequence, vararg options: LinkOption): Boolean = fileOperations.isFile(path)
        fun isHidden(path: CharSequence): Boolean = path.startsWith(".")
        fun isReadable(path: CharSequence): Boolean = exists(path)
        fun isWritable(path: CharSequence): Boolean = exists(path)
        fun isExecutable(path: CharSequence): Boolean = false
        fun getLastModifiedTime(path: CharSequence, vararg options: LinkOption): FileTime = TODO("fstat mtime")
        fun setLastModifiedTime(path: CharSequence, time: FileTime): Path = TODO("utimes")
        fun getOwner(path: CharSequence, vararg options: LinkOption): UserPrincipal = TODO("stat uid")
        fun setOwner(path: CharSequence, owner: UserPrincipal): Path = TODO("chown")
        fun isSymbolicLink(path: CharSequence): Boolean = TODO("lstat")
        fun isSameFile(path: CharSequence, other: CharSequence): Boolean = TODO("stat dev+ino")
        fun mismatch(path: CharSequence, other: CharSequence): Long = TODO("memcmp")

        fun createDirectory(path: CharSequence, vararg attrs: FileAttribute<*>): Path {
            fileOperations.mkdirs(path)
            return Paths[path]
        }
        fun createDirectories(path: CharSequence, vararg attrs: FileAttribute<*>): Path {
            fileOperations.mkdirs(path)
            return Paths[path]
        }
        fun createFile(path: CharSequence, vararg attrs: FileAttribute<*>): Path = TODO("creat")
        fun createTempFile(dir: CharSequence?, prefix: CharSequence?, suffix: CharSequence?, vararg attrs: FileAttribute<*>): Path = TODO("mkstemp")
        fun createTempDirectory(dir: CharSequence?, prefix: CharSequence?, vararg attrs: FileAttribute<*>): Path =
            Paths[fileOperations.createTempDir(prefix ?: "tmp")]

        fun delete(path: CharSequence) { deleteIfExists(path) }
        fun deleteIfExists(path: CharSequence): Boolean {
            if (!exists(path)) return false
            fileOperations.deleteRecursively(path)
            return true
        }
        fun deleteRecursively(path: CharSequence) { fileOperations.deleteRecursively(path) }
        fun list(path: CharSequence): Sequence<Path> =
            fileOperations.listDir(path).map { Paths[it] }.asSequence()
        fun newDirectoryStream(path: CharSequence): DirectoryStream<Path> = TODO("opendir")
        fun newDirectoryStream(path: CharSequence, glob: CharSequence): DirectoryStream<Path> = TODO("opendir + glob")
        fun newDirectoryStream(path: CharSequence, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = TODO("opendir + filter")
        fun lines(path: CharSequence, charset: Charset = StandardCharsets.UTF_8): Sequence<CharSequence> = readString(path, charset).lineSequence()
        fun lines(path: CharSequence): Sequence<CharSequence> = lines(path, StandardCharsets.UTF_8)

        /**
         * Returns each line as a pair of (byteOffset, lineBytes).
         * Uses [streamByteLines] which splits on `\n`, preserving the offset of each line
         * in the original file — useful for seek+read cross-validation.
         */
        fun fragments(path: CharSequence): Sequence<Join<Long, ByteArray>> =
            borg.trikeshed.lib.streamByteLines(readAllBytes(path))
        fun walk(path: CharSequence, maxDepth: Int, vararg options: FileVisitOption): Sequence<Path> = TODO("walk")
        fun walk(path: CharSequence, vararg options: FileVisitOption): Sequence<Path> = walk(path, Int.MAX_VALUE, *options)
        fun walkFileTree(path: CharSequence, options: Set<FileVisitOption>, maxDepth: Int, visitor: FileVisitor<in Path>): Path = TODO("walkFileTree")
        fun walkFileTree(path: CharSequence, visitor: FileVisitor<in Path>): Path = TODO("walkFileTree")
        fun find(path: CharSequence, maxDepth: Int, matcher: (Path, BasicFileAttributes) -> Boolean, vararg options: FileVisitOption): Sequence<Path> = TODO("find")
        fun copy(src: CharSequence, dst: CharSequence, vararg options: CopyOption): Path = TODO("copy")
        fun move(src: CharSequence, dst: CharSequence, vararg options: CopyOption): Path = TODO("rename")
        fun createLink(link: CharSequence, existing: CharSequence): Path = TODO("link")
        fun createSymbolicLink(link: CharSequence, target: CharSequence, vararg attrs: FileAttribute<*>): Path = TODO("symlink")
        fun readSymbolicLink(link: CharSequence): Path = TODO("readlink")
        fun <A : BasicFileAttributes> readAttributes(path: CharSequence, type: kotlin.reflect.KClass<A>, vararg options: LinkOption): A = TODO("stat")
        fun readAttributes(path: CharSequence, attributes: CharSequence, vararg options: LinkOption): Map<CharSequence, Any> = TODO("stat")
        fun setAttribute(path: CharSequence, attribute: CharSequence, value: Any, vararg options: LinkOption): Path = TODO("setxattr")
        fun getAttribute(path: CharSequence, attribute: CharSequence, vararg options: LinkOption): Any = TODO("getxattr")
        fun getPosixFilePermissions(path: CharSequence, vararg options: LinkOption): Set<PosixFilePermission> = TODO("stat mode")
        fun setPosixFilePermissions(path: CharSequence, perms: Set<PosixFilePermission>): Path = TODO("chmod")
        fun <V : FileAttributeView> getFileAttributeView(path: CharSequence, type: kotlin.reflect.KClass<V>, vararg options: LinkOption): V = TODO("attribute view")
        fun newInputStream(path: CharSequence, vararg options: OpenOption): Any = TODO("InputStream")
        fun newOutputStream(path: CharSequence, vararg options: OpenOption): Any = TODO("OutputStream")
        fun newBufferedReader(path: CharSequence, charset: Charset = StandardCharsets.UTF_8): Any = TODO("BufferedReader")
        fun newBufferedWriter(path: CharSequence, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Any = TODO("BufferedWriter")
        fun copy(`in`: Any, path: CharSequence, vararg options: CopyOption): Long = TODO("copy stream->file")
        fun copy(path: CharSequence, out: Any): Long = TODO("copy file->stream")
        fun getFileStore(path: CharSequence): FileStore = TODO("statfs")
        fun probeContentType(path: CharSequence): CharSequence = TODO("content type")
 fun cwd(): CharSequence = fileOperations.cwd()

        // --- Path-based overloads (delegate to CharSequence-path versions) ---

        fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
            newByteChannel(path.toString(), options, *attrs)
        fun newByteChannel(path: Path, vararg options: OpenOption): SeekableByteChannel =
            newByteChannel(path.toString(), *options)
        fun readAllBytes(path: Path): ByteArray = readAllBytes(path.toString())
        fun readString(path: Path, charset: Charset = StandardCharsets.UTF_8): CharSequence = readString(path.toString(), charset)
        fun readAllLines(path: Path, charset: Charset = StandardCharsets.UTF_8): Series<CharSequence> = readAllLines(path.toString(), charset)
        fun readAllLines(path: Path): Series<CharSequence> = readAllLines(path.toString())
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
        fun createTempFile(dir: Path?, prefix: CharSequence?, suffix: CharSequence?, vararg attrs: FileAttribute<*>): Path = createTempFile(dir?.toString(), prefix, suffix, *attrs)
        fun createTempDirectory(dir: Path?, prefix: CharSequence?, vararg attrs: FileAttribute<*>): Path = createTempDirectory(dir?.toString(), prefix, *attrs)
        fun delete(path: Path): Unit = delete(path.toString())
        fun deleteIfExists(path: Path): Boolean = deleteIfExists(path.toString())
        fun list(path: Path): Sequence<Path> = list(path.toString())
        fun newDirectoryStream(path: Path): DirectoryStream<Path> = newDirectoryStream(path.toString())
        fun newDirectoryStream(path: Path, glob: CharSequence): DirectoryStream<Path> = newDirectoryStream(path.toString(), glob)
        fun newDirectoryStream(path: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = newDirectoryStream(path.toString(), filter)
        fun lines(path: Path, charset: Charset = StandardCharsets.UTF_8): Sequence<CharSequence> = lines(path.toString(), charset)
        fun lines(path: Path): Sequence<CharSequence> = lines(path.toString())
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
        fun readAttributes(path: Path, attributes: CharSequence, vararg options: LinkOption): Map<CharSequence, Any> = readAttributes(path.toString(), attributes, *options)
        fun setAttribute(path: Path, attribute: CharSequence, value: Any, vararg options: LinkOption): Path = setAttribute(path.toString(), attribute, value, *options)
        fun getAttribute(path: Path, attribute: CharSequence, vararg options: LinkOption): Any = getAttribute(path.toString(), attribute, *options)
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
        fun probeContentType(path: Path): CharSequence = probeContentType(path.toString())
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
    override fun readAllLines(filename: CharSequence): Nothing = error("FileOperations not initialized — call PlatformProviders.init() first")
    override fun readAllBytes(filename: CharSequence): Nothing = error("FileOperations not initialized — call PlatformProviders.init() first")
    override fun readString(filename: CharSequence): Nothing = error("FileOperations not initialized — call PlatformProviders.init() first")
    override fun write(filename: CharSequence, bytes: ByteArray): Nothing = error("FileOperations not initialized")
    override fun write(filename: CharSequence, lines: Series<CharSequence>): Nothing = error("FileOperations not initialized")
    override fun write(filename: CharSequence, CharSequence: CharSequence): Nothing = error("FileOperations not initialized")
    override fun cwd(): Nothing = error("FileOperations not initialized")
    override fun exists(filename: CharSequence): Nothing = error("FileOperations not initialized")
    override fun streamLines(fileName: CharSequence, bufsize: Int): Nothing = error("FileOperations not initialized")
    override fun iterateLines(fileName: CharSequence, bufsize: Int): Nothing = error("FileOperations not initialized")
    override fun listDir(path: CharSequence): Nothing = error("FileOperations not initialized")
    override fun isDir(path: CharSequence): Boolean = error("FileOperations not initialized")
    override fun isFile(path: CharSequence): Boolean = error("FileOperations not initialized")
    override fun mkdirs(path: CharSequence): Nothing = error("FileOperations not initialized")
    override fun deleteRecursively(path: CharSequence): Nothing = error("FileOperations not initialized")
    override fun resolvePath(vararg parts: CharSequence): Nothing = error("FileOperations not initialized")
    override fun readZip(path: CharSequence): Nothing = error("FileOperations not initialized")
    override fun createTempDir(prefix: CharSequence): Nothing = error("FileOperations not initialized")
}
