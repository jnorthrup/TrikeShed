@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect interface FileVisitor<T> {
    fun preVisitDirectory(p0: T, p1: borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes): borg.trikeshed.userspace.nio.file.FileVisitResult
    fun visitFile(p0: T, p1: borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes): borg.trikeshed.userspace.nio.file.FileVisitResult
    fun visitFileFailed(p0: T, p1: java.io.IOException): borg.trikeshed.userspace.nio.file.FileVisitResult
    fun postVisitDirectory(p0: T, p1: java.io.IOException): borg.trikeshed.userspace.nio.file.FileVisitResult
}
