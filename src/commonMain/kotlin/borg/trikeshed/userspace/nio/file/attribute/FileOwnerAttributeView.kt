@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface FileOwnerAttributeView : FileAttributeView {
    override fun name(): String = "owner"
    fun getOwner(): UserPrincipal
    fun setOwner(p0: UserPrincipal): Unit
}
