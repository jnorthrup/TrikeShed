@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect interface AclFileAttributeView : borg.trikeshed.userspace.nio.file.attribute.FileOwnerAttributeView {
    fun name(): String
    fun getAcl(): java.util.List<borg.trikeshed.userspace.nio.file.attribute.AclEntry>
    fun setAcl(p0: java.util.List<borg.trikeshed.userspace.nio.file.attribute.AclEntry>): Unit
}
