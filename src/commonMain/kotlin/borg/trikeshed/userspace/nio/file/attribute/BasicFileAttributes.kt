@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface BasicFileAttributes {
    fun lastModifiedTime(): borg.trikeshed.userspace.nio.file.attribute.FileTime = TODO("NIO common stub")
    fun lastAccessTime(): borg.trikeshed.userspace.nio.file.attribute.FileTime = TODO("NIO common stub")
    fun creationTime(): borg.trikeshed.userspace.nio.file.attribute.FileTime = TODO("NIO common stub")
    fun isRegularFile(): Boolean = TODO("NIO common stub")
    fun isDirectory(): Boolean = TODO("NIO common stub")
    fun isSymbolicLink(): Boolean = TODO("NIO common stub")
    fun isOther(): Boolean = TODO("NIO common stub")
    fun size(): Long = TODO("NIO common stub")
    fun fileKey(): Any = TODO("NIO common stub")
}
