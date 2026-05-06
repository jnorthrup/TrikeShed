@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class SimpleFileVisitor<T> {
    protected constructor()
    fun preVisitDirectory(p0: T, p1: borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes): borg.trikeshed.userspace.nio.file.FileVisitResult = TODO("NIO common stub")
    fun visitFile(p0: T, p1: borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes): borg.trikeshed.userspace.nio.file.FileVisitResult = TODO("NIO common stub")
    fun visitFileFailed(p0: T, p1: borg.trikeshed.userspace.nio.IOException): borg.trikeshed.userspace.nio.file.FileVisitResult = TODO("NIO common stub")
    fun postVisitDirectory(p0: T, p1: borg.trikeshed.userspace.nio.IOException): borg.trikeshed.userspace.nio.file.FileVisitResult = TODO("NIO common stub")
}
