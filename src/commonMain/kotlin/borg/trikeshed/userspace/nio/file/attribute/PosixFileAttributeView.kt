@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface PosixFileAttributeView : BasicFileAttributeView, FileOwnerAttributeView {
    override fun name(): String = "posix"
    override fun readAttributes(): PosixFileAttributes
    fun setPermissions(p0: Set<PosixFilePermission>): Unit
    fun setGroup(p0: GroupPrincipal): Unit
}
