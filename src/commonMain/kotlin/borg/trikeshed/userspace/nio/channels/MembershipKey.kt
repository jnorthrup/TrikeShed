@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class MembershipKey {
    protected constructor()
    public abstract fun isValid(): Boolean
    public abstract fun drop(): Unit
    public abstract fun block(sourceAddress: String): MembershipKey
    public abstract fun unblock(sourceAddress: String): MembershipKey
    public abstract fun channel(): MulticastChannel
    public abstract fun group(): String
    public abstract fun networkInterface(): String
    public abstract fun sourceAddress(): String
}
