@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileSystemProvider {
    protected constructor()
    abstract fun getScheme(): String
    abstract fun newFileSystem(p0: String, p1: Map<String, *>): borg.trikeshed.userspace.nio.file.FileSystem
    abstract fun getFileSystem(p0: String): borg.trikeshed.userspace.nio.file.FileSystem
    abstract fun getPath(p0: String): borg.trikeshed.userspace.nio.file.Path
    abstract fun newFileSystem(p0: borg.trikeshed.userspace.nio.file.Path, p1: Map<String, *>): borg.trikeshed.userspace.nio.file.FileSystem
    abstract fun newInputStream(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): Any
    abstract fun newOutputStream(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): Any
    abstract fun newFileChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.FileChannel
    abstract fun newAsynchronousFileChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: Set<out borg.trikeshed.userspace.nio.file.OpenOption>, p2: Any, vararg p3: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.AsynchronousFileChannel
    abstract fun newByteChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.SeekableByteChannel
    abstract fun newDirectoryStream(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.DirectoryStream.Filter<in borg.trikeshed.userspace.nio.file.Path>): borg.trikeshed.userspace.nio.file.DirectoryStream<borg.trikeshed.userspace.nio.file.Path>
    abstract fun createDirectory(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): Unit
    abstract fun createSymbolicLink(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): Unit
    abstract fun createLink(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path): Unit
    abstract fun delete(p0: borg.trikeshed.userspace.nio.file.Path): Unit
    abstract fun deleteIfExists(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
    abstract fun readSymbolicLink(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path
    abstract fun copy(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.CopyOption): Unit
    abstract fun move(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.CopyOption): Unit
    abstract fun isSameFile(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path): Boolean
    abstract fun isHidden(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
    abstract fun getFileStore(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.FileStore
    abstract fun checkAccess(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.AccessMode): Unit
    abstract fun <V : borg.trikeshed.userspace.nio.file.attribute.FileAttributeView> getFileAttributeView(p0: borg.trikeshed.userspace.nio.file.Path, p1: kotlin.reflect.KClass<V>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): V
    abstract fun <A : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes> readAttributes(p0: borg.trikeshed.userspace.nio.file.Path, p1: kotlin.reflect.KClass<A>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): A
    abstract fun readAttributes(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): Map<String, Any>
    abstract fun setAttribute(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, p2: Any, vararg p3: borg.trikeshed.userspace.nio.file.LinkOption): Unit
    abstract fun exists(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): Boolean
    abstract fun <A : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes> readAttributesIfExists(p0: borg.trikeshed.userspace.nio.file.Path, p1: kotlin.reflect.KClass<A>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): A
    companion object {
        fun installedProviders(): List<borg.trikeshed.userspace.nio.file.spi.FileSystemProvider> = emptyList()
    }
}
