@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect interface BasicFileAttributes {
    fun lastModifiedTime(): borg.trikeshed.userspace.nio.file.attribute.FileTime
    fun lastAccessTime(): borg.trikeshed.userspace.nio.file.attribute.FileTime
    fun creationTime(): borg.trikeshed.userspace.nio.file.attribute.FileTime
    fun isRegularFile(): Boolean
    fun isDirectory(): Boolean
    fun isSymbolicLink(): Boolean
    fun isOther(): Boolean
    fun size(): Long
    fun fileKey(): Any
}
