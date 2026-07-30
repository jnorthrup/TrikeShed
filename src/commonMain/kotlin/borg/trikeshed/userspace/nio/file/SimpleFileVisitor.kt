@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class SimpleFileVisitor<T> : FileVisitor<T> {
    protected constructor()
    override fun preVisitDirectory(p0: T, p1: borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes): borg.trikeshed.userspace.nio.file.FileVisitResult = FileVisitResult.CONTINUE
    override fun visitFile(p0: T, p1: borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes): borg.trikeshed.userspace.nio.file.FileVisitResult = FileVisitResult.CONTINUE
    override fun visitFileFailed(p0: T, p1: borg.trikeshed.userspace.nio.IOException): borg.trikeshed.userspace.nio.file.FileVisitResult = throw p1
    override fun postVisitDirectory(p0: T, p1: borg.trikeshed.userspace.nio.IOException?): borg.trikeshed.userspace.nio.file.FileVisitResult { if (p1 != null) throw p1; return FileVisitResult.CONTINUE }
}
