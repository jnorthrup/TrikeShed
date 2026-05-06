@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface SecureDirectoryStream<T> : borg.trikeshed.userspace.nio.file.DirectoryStream<T> {
    fun newDirectoryStream(p0: T, vararg p1: borg.trikeshed.userspace.nio.file.LinkOption): borg.trikeshed.userspace.nio.file.SecureDirectoryStream<T> = TODO("NIO common stub")
    fun newByteChannel(p0: T, p1: java.util.Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.SeekableByteChannel = TODO("NIO common stub")
    fun deleteFile(p0: T): Unit = TODO("NIO common stub")
    fun deleteDirectory(p0: T): Unit = TODO("NIO common stub")
    fun move(p0: T, p1: borg.trikeshed.userspace.nio.file.SecureDirectoryStream<T>, p2: T): Unit = TODO("NIO common stub")
    fun <V : borg.trikeshed.userspace.nio.file.attribute.FileAttributeView> getFileAttributeView(p0: java.lang.Class<V>): V = TODO("NIO common stub")
    fun <V : borg.trikeshed.userspace.nio.file.attribute.FileAttributeView> getFileAttributeView(p0: T, p1: java.lang.Class<V>, vararg p2: borg.trikeshed.userspace.nio.file.LinkOption): V = TODO("NIO common stub")
}
