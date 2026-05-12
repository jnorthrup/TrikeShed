@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.userspace.nio.channels.AsynchronousFileChannel
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.SeekableByteChannel
import borg.trikeshed.userspace.nio.file.*
import borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes
import borg.trikeshed.userspace.nio.file.attribute.FileAttribute
import borg.trikeshed.userspace.nio.file.attribute.FileAttributeView
import kotlin.reflect.KClass

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
interface FileSystemProvider {

    fun getScheme(): CharSequence// TODO("NIO common stub")
    fun newFileSystem(p0: CharSequence, p1: Map<CharSequence, *>): FileSystem// TODO("NIO common stub")
    fun getFileSystem(p0: CharSequence): FileSystem// TODO("NIO common stub")
    fun getPath(p0: CharSequence): Path// TODO("NIO common stub")
    fun newFileSystem(p0: Path, p1: Map<CharSequence, *>): FileSystem// TODO("NIO common stub")
    fun newInputStream(p0: Path, vararg p1: OpenOption): Any// TODO("NIO common stub")
    fun newOutputStream(p0: Path, vararg p1: OpenOption): Any// TODO("NIO common stub")
    fun newFileChannel(p0: Path, p1: Set<OpenOption>, vararg p2: FileAttribute<*>): FileChannel// TODO("NIO common stub")
    fun newAsynchronousFileChannel(p0: Path, p1: Set<OpenOption>, p2: Any, vararg p3: FileAttribute<*>): AsynchronousFileChannel// TODO("NIO common stub")
    fun newByteChannel(p0: Path, p1: Set<OpenOption>, vararg p2: FileAttribute<*>): SeekableByteChannel// TODO("NIO common stub")
    fun newDirectoryStream(p0: Path, p1: DirectoryStream.Filter<in Path>): DirectoryStream<Path>// TODO("NIO common stub")
    fun createDirectory(p0: Path, vararg p1: FileAttribute<*>)// TODO("NIO common stub")
    fun createSymbolicLink(p0: Path, p1: Path, vararg p2: FileAttribute<*>)// TODO("NIO common stub")
    fun createLink(p0: Path, p1: Path)// TODO("NIO common stub")
    fun delete(p0: Path)// TODO("NIO common stub")
    fun deleteIfExists(p0: Path): Boolean// TODO("NIO common stub")
    fun readSymbolicLink(p0: Path): Path// TODO("NIO common stub")
    fun copy(p0: Path, p1: Path, vararg p2: CopyOption)// TODO("NIO common stub")
    fun move(p0: Path, p1: Path, vararg p2: CopyOption)// TODO("NIO common stub")
    fun isSameFile(p0: Path, p1: Path): Boolean// TODO("NIO common stub")
    fun isHidden(p0: Path): Boolean// TODO("NIO common stub")
    fun getFileStore(p0: Path): FileStore// TODO("NIO common stub")
    fun checkAccess(p0: Path, vararg p1: AccessMode)// TODO("NIO common stub")
    fun <V : FileAttributeView> getFileAttributeView(p0: Path, p1: KClass<V>, vararg p2: LinkOption): V// TODO("NIO common stub")
    fun <A : BasicFileAttributes> readAttributes(p0: Path, p1: KClass<A>, vararg p2: LinkOption): A// TODO("NIO common stub")
    fun readAttributes(p0: Path, p1: CharSequence, vararg p2: LinkOption): Map<CharSequence, Any>// TODO("NIO common stub")
    fun setAttribute(p0: Path, p1: CharSequence, p2: Any, vararg p3: LinkOption)// TODO("NIO common stub")
    fun exists(p0: Path, vararg p1: LinkOption): Boolean// TODO("NIO common stub")
    fun <A : BasicFileAttributes> readAttributesIfExists(p0: Path, p1: KClass<A>, vararg p2: LinkOption): A// TODO("NIO common stub")
//    companion object {
//        fun installedProviders(): List<FileSystemProvider>// TODO("NIO common stub")
//    }
}
