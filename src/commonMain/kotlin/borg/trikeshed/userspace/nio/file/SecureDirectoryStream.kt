@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface SecureDirectoryStream<T> : DirectoryStream<T> {
    fun newDirectoryStream(p0: T, vararg p1: LinkOption): SecureDirectoryStream<T> // TODO("NIO common stub")
    fun newByteChannel(p0: T, p1: Set<out OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.SeekableByteChannel // TODO("NIO common stub")
    fun deleteFile(p0: T): Unit // TODO("NIO common stub")
    fun deleteDirectory(p0: T): Unit // TODO("NIO common stub")
    fun move(p0: T, p1: SecureDirectoryStream<T>, p2: T): Unit // TODO("NIO common stub")
    fun <V : borg.trikeshed.userspace.nio.file.attribute.FileAttributeView> getFileAttributeView(p0: kotlin.reflect.KClass<V>): V // TODO("NIO common stub")
    fun <V : borg.trikeshed.userspace.nio.file.attribute.FileAttributeView> getFileAttributeView(p0: T, p1: kotlin.reflect.KClass<V>, vararg p2: LinkOption): V // TODO("NIO common stub")
}
