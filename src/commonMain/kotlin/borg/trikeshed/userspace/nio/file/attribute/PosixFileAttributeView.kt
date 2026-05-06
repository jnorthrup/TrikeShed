@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface PosixFileAttributeView : BasicFileAttributeView, FileOwnerAttributeView {
    override fun name(): String = TODO("NIO common stub")
    override fun readAttributes(): PosixFileAttributes = TODO("NIO common stub")
    fun setPermissions(p0: Set<PosixFilePermission>): Unit = TODO("NIO common stub")
    fun setGroup(p0: GroupPrincipal): Unit = TODO("NIO common stub")
}
