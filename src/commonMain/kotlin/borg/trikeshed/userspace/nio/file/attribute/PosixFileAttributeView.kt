@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface PosixFileAttributeView : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributeView, borg.trikeshed.userspace.nio.file.attribute.FileOwnerAttributeView {
    fun name(): String = TODO("NIO common stub")
    fun readAttributes(): borg.trikeshed.userspace.nio.file.attribute.PosixFileAttributes = TODO("NIO common stub")
    fun setPermissions(p0: java.util.Set<borg.trikeshed.userspace.nio.file.attribute.PosixFilePermission>): Unit = TODO("NIO common stub")
    fun setGroup(p0: borg.trikeshed.userspace.nio.file.attribute.GroupPrincipal): Unit = TODO("NIO common stub")
}
