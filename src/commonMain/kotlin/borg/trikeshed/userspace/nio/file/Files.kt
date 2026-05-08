@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.file

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.SeekableByteChannel
import borg.trikeshed.userspace.nio.charset.Charset
import borg.trikeshed.userspace.nio.charset.StandardCharsets
import borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes
import borg.trikeshed.userspace.nio.file.attribute.FileAttribute
import borg.trikeshed.userspace.nio.file.attribute.FileAttributeView
import borg.trikeshed.userspace.nio.file.attribute.FileTime
import borg.trikeshed.userspace.nio.file.attribute.PosixFilePermission
import borg.trikeshed.userspace.nio.file.attribute.UserPrincipal

public class Files {
    companion object {
        fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
            FileChannel.open(path, options, *attrs)
        fun newByteChannel(path: Path, vararg options: OpenOption): SeekableByteChannel =
            FileChannel.open(path, *options)

        fun readAllBytes(path: Path): ByteArray {
            val ch = FileChannel.open(path, StandardOpenOption.READ)
            val sz = size(path).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val bytes = ByteArray(sz)
            val buf = ByteBuffer(bytes)
            ch.use { it.read(buf) }
            return bytes
        }

        fun readString(path: Path, charset: Charset = StandardCharsets.UTF_8): String =
            readAllBytes(path).decodeToString()

        fun readAllLines(path: Path, charset: Charset = StandardCharsets.UTF_8): List<String> =
            readString(path, charset).split('\n')
        fun readAllLines(path: Path): List<String> = readAllLines(path, StandardCharsets.UTF_8)

        fun write(path: Path, bytes: ByteArray, vararg options: OpenOption): Path {
            val ch = FileChannel.open(path, *options)
            ch.use { it.write(ByteBuffer(bytes)) }
            return path
        }

        fun write(path: Path, lines: Iterable<CharSequence>, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Path {
            val bytes: ByteArray = lines.joinToString("\n").encodeToByteArray()
            return write(path, bytes, *options)
        }
        fun write(path: Path, lines: Iterable<CharSequence>, vararg options: OpenOption): Path =
            write(path, lines, StandardCharsets.UTF_8, *options)
        fun writeString(path: Path, csq: CharSequence, vararg options: OpenOption): Path =
            write(path, csq.toString().encodeToByteArray(), *options)
        fun writeString(path: Path, csq: CharSequence, charset: Charset, vararg options: OpenOption): Path {
            val bytes: ByteArray = csq.toString().encodeToByteArray()
            return write(path, bytes, *options)
        }

        fun exists(path: Path, vararg options: LinkOption): Boolean =
            borg.trikeshed.userspace.Files.let { facade ->
                try { facade.open(path.toString(), readOnly = true).close(); true }
                catch (_: Exception) { false }
            }
        fun notExists(path: Path, vararg options: LinkOption): Boolean = !exists(path, *options)
        fun size(path: Path): Long {
            val ch = FileChannel.open(path, StandardOpenOption.READ)
            return ch.use { it.size() }
        }
        fun isDirectory(path: Path, vararg options: LinkOption): Boolean = TODO("fstat S_ISDIR")
        fun isRegularFile(path: Path, vararg options: LinkOption): Boolean = TODO("fstat S_ISREG")
        fun isHidden(path: Path): Boolean = path.toString().startsWith(".")
        fun isReadable(path: Path): Boolean = exists(path)
        fun isWritable(path: Path): Boolean = exists(path)
        fun isExecutable(path: Path): Boolean = false
        fun getLastModifiedTime(path: Path, vararg options: LinkOption): FileTime = TODO("fstat mtime")
        fun setLastModifiedTime(path: Path, time: FileTime): Path = TODO("utimes")
        fun getOwner(path: Path, vararg options: LinkOption): UserPrincipal = TODO("stat uid")
        fun setOwner(path: Path, owner: UserPrincipal): Path = TODO("chown")
        fun isSymbolicLink(path: Path): Boolean = TODO("lstat")
        fun isSameFile(path: Path, other: Path): Boolean = TODO("stat dev+ino")
        fun mismatch(path: Path, other: Path): Long = TODO("memcmp")
        fun createDirectory(path: Path, vararg attrs: FileAttribute<*>): Path = TODO("mkdir")
        fun createDirectories(path: Path, vararg attrs: FileAttribute<*>): Path = TODO("mkdir -p")
        fun createFile(path: Path, vararg attrs: FileAttribute<*>): Path = TODO("creat")
        fun createTempFile(dir: Path?, prefix: String?, suffix: String?, vararg attrs: FileAttribute<*>): Path = TODO("mkstemp")
        fun createTempFile(prefix: String?, suffix: String?, vararg attrs: FileAttribute<*>): Path = createTempFile(null, prefix, suffix, *attrs)
        fun createTempDirectory(dir: Path?, prefix: String?, vararg attrs: FileAttribute<*>): Path = TODO("mkdtemp")
        fun createTempDirectory(prefix: String?, vararg attrs: FileAttribute<*>): Path = createTempDirectory(null, prefix, *attrs)
        fun delete(path: Path): Unit { deleteIfExists(path) }
        fun deleteIfExists(path: Path): Boolean = TODO("unlink")
        fun list(path: Path): Sequence<Path> = TODO("opendir/readdir")
        fun newDirectoryStream(path: Path): DirectoryStream<Path> = TODO("opendir")
        fun newDirectoryStream(path: Path, glob: String): DirectoryStream<Path> = TODO("opendir + glob")
        fun newDirectoryStream(path: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = TODO("opendir + filter")
        fun lines(path: Path, charset: Charset = StandardCharsets.UTF_8): Sequence<String> = readString(path, charset).lineSequence()
        fun lines(path: Path): Sequence<String> = lines(path, StandardCharsets.UTF_8)
        fun walk(path: Path, maxDepth: Int, vararg options: FileVisitOption): Sequence<Path> = TODO("walk")
        fun walk(path: Path, vararg options: FileVisitOption): Sequence<Path> = walk(path, Int.MAX_VALUE, *options)
        fun walkFileTree(path: Path, options: Set<FileVisitOption>, maxDepth: Int, visitor: FileVisitor<in Path>): Path = TODO("walkFileTree")
        fun walkFileTree(path: Path, visitor: FileVisitor<in Path>): Path = TODO("walkFileTree")
        fun find(path: Path, maxDepth: Int, matcher: (Path, BasicFileAttributes) -> Boolean, vararg options: FileVisitOption): Sequence<Path> = TODO("find")
        fun copy(src: Path, dst: Path, vararg options: CopyOption): Path = TODO("copy")
        fun move(src: Path, dst: Path, vararg options: CopyOption): Path = TODO("rename")
        fun createLink(link: Path, existing: Path): Path = TODO("link")
        fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>): Path = TODO("symlink")
        fun readSymbolicLink(link: Path): Path = TODO("readlink")
        fun <A : BasicFileAttributes> readAttributes(path: Path, type: kotlin.reflect.KClass<A>, vararg options: LinkOption): A = TODO("stat")
        fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> = TODO("stat")
        fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption): Path = TODO("setxattr")
        fun getAttribute(path: Path, attribute: String, vararg options: LinkOption): Any = TODO("getxattr")
        fun getPosixFilePermissions(path: Path, vararg options: LinkOption): Set<PosixFilePermission> = TODO("stat mode")
        fun setPosixFilePermissions(path: Path, perms: Set<PosixFilePermission>): Path = TODO("chmod")
        fun <V : FileAttributeView> getFileAttributeView(path: Path, type: kotlin.reflect.KClass<V>, vararg options: LinkOption): V = TODO("attribute view")
        fun newInputStream(path: Path, vararg options: OpenOption): Any = TODO("InputStream")
        fun newOutputStream(path: Path, vararg options: OpenOption): Any = TODO("OutputStream")
        fun newBufferedReader(path: Path, charset: Charset = StandardCharsets.UTF_8): Any = TODO("BufferedReader")
        fun newBufferedWriter(path: Path, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Any = TODO("BufferedWriter")
        fun copy(`in`: Any, path: Path, vararg options: CopyOption): Long = TODO("copy stream->file")
        fun copy(path: Path, out: Any): Long = TODO("copy file->stream")
        fun getFileStore(path: Path): FileStore = TODO("statfs")
        fun probeContentType(path: Path): String = TODO("content type")
    }
}
