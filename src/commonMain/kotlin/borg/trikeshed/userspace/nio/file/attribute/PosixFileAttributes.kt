@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface PosixFileAttributes : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributes {
    fun owner(): borg.trikeshed.userspace.nio.file.attribute.UserPrincipal
    fun group(): borg.trikeshed.userspace.nio.file.attribute.GroupPrincipal
    fun permissions(): java.util.Set<borg.trikeshed.userspace.nio.file.attribute.PosixFilePermission>
}
