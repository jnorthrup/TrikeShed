@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect class Files {
    companion object {
        fun newInputStream(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): java.io.InputStream
        fun newOutputStream(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): java.io.OutputStream
        fun newByteChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.SeekableByteChannel
        fun newByteChannel(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): borg.trikeshed.userspace.nio.channels.SeekableByteChannel
        fun newDirectoryStream(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.DirectoryStream<borg.trikeshed.userspace.nio.file.Path>
        fun newDirectoryStream(p0: borg.trikeshed.userspace.nio.file.Path, p1: String): borg.trikeshed.userspace.nio.file.DirectoryStream<borg.trikeshed.userspace.nio.file.Path>
        fun newDirectoryStream(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.DirectoryStream.Filter<in borg.trikeshed.userspace.nio.file.Path>): borg.trikeshed.userspace.nio.file.DirectoryStream<borg.trikeshed.userspace.nio.file.Path>
        fun createFile(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.file.Path
        fun createDirectory(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.file.Path
        fun createDirectories(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.file.Path
        fun createTempFile(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, p2: String, vararg p3: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.file.Path
        fun createTempFile(p0: String, p1: String, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.file.Path
        fun createTempDirectory(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.file.Path
        fun createTempDirectory(p0: String, vararg p1: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.file.Path
        fun createSymbolicLink(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.file.Path
        fun createLink(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path
        fun delete(p0: borg.trikeshed.userspace.nio.file.Path): Unit
        fun deleteIfExists(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
        fun copy(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.CopyOption): borg.trikeshed.userspace.nio.file.Path
        fun move(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.CopyOption): borg.trikeshed.userspace.nio.file.Path
        fun readSymbolicLink(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path
        fun getFileStore(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.FileStore
        fun isSameFile(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path): Boolean
        fun mismatch(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path): Long
        fun isHidden(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
        fun probeContentType(p0: borg.trikeshed.userspace.nio.file.Path): String
        fun <V : borg.trikeshed.userspace.nio.file.attribute.FileAttributeView> getFileAttributeView(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.lang.Class<V>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): V
        fun <A : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes> readAttributes(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.lang.Class<A>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): A
        fun setAttribute(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, p2: Any, vararg p3: borg.trikeshed.userspace.nio.file.LinkOption): borg.trikeshed.userspace.nio.file.Path
        fun getAttribute(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): Any
        fun readAttributes(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): java.util.Map<String, Any>
        fun getPosixFilePermissions(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): java.util.Set<borg.trikeshed.userspace.nio.file.attribute.PosixFilePermission>
        fun setPosixFilePermissions(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Set<borg.trikeshed.userspace.nio.file.attribute.PosixFilePermission>): borg.trikeshed.userspace.nio.file.Path
        fun getOwner(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): borg.trikeshed.userspace.nio.file.attribute.UserPrincipal
        fun setOwner(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.attribute.UserPrincipal): borg.trikeshed.userspace.nio.file.Path
        fun isSymbolicLink(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
        fun isDirectory(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): Boolean
        fun isRegularFile(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): Boolean
        fun getLastModifiedTime(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): borg.trikeshed.userspace.nio.file.attribute.FileTime
        fun setLastModifiedTime(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.attribute.FileTime): borg.trikeshed.userspace.nio.file.Path
        fun size(p0: borg.trikeshed.userspace.nio.file.Path): Long
        fun exists(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): Boolean
        fun notExists(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): Boolean
        fun isReadable(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
        fun isWritable(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
        fun isExecutable(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
        fun walkFileTree(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Set<borg.trikeshed.userspace.nio.file.FileVisitOption>, p2: Int, p3: borg.trikeshed.userspace.nio.file.FileVisitor<in borg.trikeshed.userspace.nio.file.Path>): borg.trikeshed.userspace.nio.file.Path
        fun walkFileTree(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.FileVisitor<in borg.trikeshed.userspace.nio.file.Path>): borg.trikeshed.userspace.nio.file.Path
        fun newBufferedReader(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.charset.Charset): java.io.BufferedReader
        fun newBufferedReader(p0: borg.trikeshed.userspace.nio.file.Path): java.io.BufferedReader
        fun newBufferedWriter(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.charset.Charset, vararg p2: borg.trikeshed.userspace.nio.file.OpenOption): java.io.BufferedWriter
        fun newBufferedWriter(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): java.io.BufferedWriter
        fun copy(p0: java.io.InputStream, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.CopyOption): Long
        fun copy(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.io.OutputStream): Long
        fun readAllBytes(p0: borg.trikeshed.userspace.nio.file.Path): ByteArray
        fun readString(p0: borg.trikeshed.userspace.nio.file.Path): String
        fun readString(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.charset.Charset): String
        fun readAllLines(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.charset.Charset): java.util.List<String>
        fun readAllLines(p0: borg.trikeshed.userspace.nio.file.Path): java.util.List<String>
        fun write(p0: borg.trikeshed.userspace.nio.file.Path, p1: ByteArray, vararg p2: borg.trikeshed.userspace.nio.file.OpenOption): borg.trikeshed.userspace.nio.file.Path
        fun write(p0: borg.trikeshed.userspace.nio.file.Path, p1: Iterable<out CharSequence>, p2: borg.trikeshed.userspace.nio.charset.Charset, vararg p3: borg.trikeshed.userspace.nio.file.OpenOption): borg.trikeshed.userspace.nio.file.Path
        fun write(p0: borg.trikeshed.userspace.nio.file.Path, p1: Iterable<out CharSequence>, vararg p2: borg.trikeshed.userspace.nio.file.OpenOption): borg.trikeshed.userspace.nio.file.Path
        fun writeString(p0: borg.trikeshed.userspace.nio.file.Path, p1: CharSequence, vararg p2: borg.trikeshed.userspace.nio.file.OpenOption): borg.trikeshed.userspace.nio.file.Path
        fun writeString(p0: borg.trikeshed.userspace.nio.file.Path, p1: CharSequence, p2: borg.trikeshed.userspace.nio.charset.Charset, vararg p3: borg.trikeshed.userspace.nio.file.OpenOption): borg.trikeshed.userspace.nio.file.Path
        fun list(p0: borg.trikeshed.userspace.nio.file.Path): java.util.stream.Stream<borg.trikeshed.userspace.nio.file.Path>
        fun walk(p0: borg.trikeshed.userspace.nio.file.Path, p1: Int, vararg p2: borg.trikeshed.userspace.nio.file.FileVisitOption): java.util.stream.Stream<borg.trikeshed.userspace.nio.file.Path>
        fun walk(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.FileVisitOption): java.util.stream.Stream<borg.trikeshed.userspace.nio.file.Path>
        fun find(p0: borg.trikeshed.userspace.nio.file.Path, p1: Int, p2: java.util.function.BiPredicate<borg.trikeshed.userspace.nio.file.Path, borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes>, vararg p3: borg.trikeshed.userspace.nio.file.FileVisitOption): java.util.stream.Stream<borg.trikeshed.userspace.nio.file.Path>
        fun lines(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.charset.Charset): java.util.stream.Stream<String>
        fun lines(p0: borg.trikeshed.userspace.nio.file.Path): java.util.stream.Stream<String>
    }
}
