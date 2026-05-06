@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileSystemProvider {
    protected constructor()
    fun getScheme(): String = TODO("NIO common stub")
    fun newFileSystem(p0: String, p1: Map<String, *>): borg.trikeshed.userspace.nio.file.FileSystem = TODO("NIO common stub")
    fun getFileSystem(p0: String): borg.trikeshed.userspace.nio.file.FileSystem = TODO("NIO common stub")
    fun getPath(p0: String): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun newFileSystem(p0: borg.trikeshed.userspace.nio.file.Path, p1: Map<String, *>): borg.trikeshed.userspace.nio.file.FileSystem = TODO("NIO common stub")
    fun newInputStream(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): Any = TODO("NIO common stub")
    fun newOutputStream(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): Any = TODO("NIO common stub")
    fun newFileChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.FileChannel = TODO("NIO common stub")
    fun newAsynchronousFileChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: Set<out borg.trikeshed.userspace.nio.file.OpenOption>, p2: Any, vararg p3: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.AsynchronousFileChannel = TODO("NIO common stub")
    fun newByteChannel(p0: borg.trikeshed.userspace.nio.file.Path, p1: Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.SeekableByteChannel = TODO("NIO common stub")
    fun newDirectoryStream(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.DirectoryStream.Filter<in borg.trikeshed.userspace.nio.file.Path>): borg.trikeshed.userspace.nio.file.DirectoryStream<borg.trikeshed.userspace.nio.file.Path> = TODO("NIO common stub")
    fun createDirectory(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): Unit = TODO("NIO common stub")
    fun createSymbolicLink(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): Unit = TODO("NIO common stub")
    fun createLink(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path): Unit = TODO("NIO common stub")
    fun delete(p0: borg.trikeshed.userspace.nio.file.Path): Unit = TODO("NIO common stub")
    fun deleteIfExists(p0: borg.trikeshed.userspace.nio.file.Path): Boolean = TODO("NIO common stub")
    fun readSymbolicLink(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun copy(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.CopyOption): Unit = TODO("NIO common stub")
    fun move(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path, vararg p2: borg.trikeshed.userspace.nio.file.CopyOption): Unit = TODO("NIO common stub")
    fun isSameFile(p0: borg.trikeshed.userspace.nio.file.Path, p1: borg.trikeshed.userspace.nio.file.Path): Boolean = TODO("NIO common stub")
    fun isHidden(p0: borg.trikeshed.userspace.nio.file.Path): Boolean = TODO("NIO common stub")
    fun getFileStore(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.FileStore = TODO("NIO common stub")
    fun checkAccess(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.AccessMode): Unit = TODO("NIO common stub")
    fun <V : borg.trikeshed.userspace.nio.file.attribute.FileAttributeView> getFileAttributeView(p0: borg.trikeshed.userspace.nio.file.Path, p1: kotlin.reflect.KClass<V>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): V = TODO("NIO common stub")
    fun <A : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes> readAttributes(p0: borg.trikeshed.userspace.nio.file.Path, p1: kotlin.reflect.KClass<A>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): A = TODO("NIO common stub")
    fun readAttributes(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): Map<String, Any> = TODO("NIO common stub")
    fun setAttribute(p0: borg.trikeshed.userspace.nio.file.Path, p1: String, p2: Any, vararg p3: borg.trikeshed.userspace.nio.file.LinkOption): Unit = TODO("NIO common stub")
    fun exists(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): Boolean = TODO("NIO common stub")
    fun <A : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes> readAttributesIfExists(p0: borg.trikeshed.userspace.nio.file.Path, p1: kotlin.reflect.KClass<A>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): A = TODO("NIO common stub")
    companion object {
        fun installedProviders(): List<borg.trikeshed.userspace.nio.file.spi.FileSystemProvider> = TODO("NIO common stub")
    }
}
