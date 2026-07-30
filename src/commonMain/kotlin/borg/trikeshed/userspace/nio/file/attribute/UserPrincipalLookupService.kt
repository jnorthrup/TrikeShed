@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class UserPrincipalLookupService {
    protected constructor()
    abstract fun lookupPrincipalByName(p0: String): borg.trikeshed.userspace.nio.file.attribute.UserPrincipal
    abstract fun lookupPrincipalByGroupName(p0: String): borg.trikeshed.userspace.nio.file.attribute.GroupPrincipal
}
