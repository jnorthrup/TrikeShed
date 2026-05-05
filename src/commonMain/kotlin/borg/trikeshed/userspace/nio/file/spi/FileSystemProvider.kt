@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileSystemProvider {
    protected constructor()
    fun getScheme(): String
    fun newFileSystem(p0: java.net.URI, p1: java.util.Map<String, *>): borg.trikeshed.userspace.nio.file.FileSystem
    fun getFileSystem(p0: java.net.URI): borg.trikeshed.userspace.nio.file.FileSystem
    fun getPath(p0: java.net.URI): borg.trikeshed.userspace.nio.file.Path
    fun newFileSystem(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Map<String, *>): borg.trikeshed.userspace.nio.file.FileSystem
    fun newInputStream(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): java.io.InputStream
    fun newOutputStream(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): java.io.OutputStream
    fun newFileChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.FileChannel
    fun newAsynchronousFileChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Set<out borg.trikeshed.userspace.nio.file.OpenOption>, p2: java.util.concurrent.ExecutorService, vararg p3: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.AsynchronousFileChannel
    fun newByteChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.SeekableByteChannel
    fun newDirectoryStream(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.DirectoryStream.Filter<in borg.trikeshed.userspace.nio.file.Path>): borg.trikeshed.userspace.nio.file.DirectoryStream<borg.trikeshed.userspace.nio.file.Path>
    fun createDirectory(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): Unit
    fun createSymbolicLink(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): Unit
    fun createLink(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path): Unit
    fun delete(p0: borg.trikeshed.userspace.nio.file.Path): Unit
    fun deleteIfExists(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
    fun readSymbolicLink(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path
    fun copy(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.CopyOption): Unit
    fun move(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.CopyOption): Unit
    fun isSameFile(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path): Boolean
    fun isHidden(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
    fun getFileStore(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.FileStore
    fun checkAccess(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.AccessMode): Unit
    fun <V : borg.trikeshed.userspace.nio.file.attribute.FileAttributeView> getFileAttributeView(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.lang.Class<V>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): V
    fun <A : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes> readAttributes(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.lang.Class<A>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): A
    fun readAttributes(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): java.util.Map<String, Any>
    fun setAttribute(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, p2: Any, vararg p3: borg.trikeshed.userspace.nio.file.LinkOption): Unit
    fun exists(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): Boolean
    fun <A : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes> readAttributesIfExists(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.lang.Class<A>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): A
    companion object {
        fun installedProviders(): java.util.List<borg.trikeshed.userspace.nio.file.spi.FileSystemProvider>
    }
}
