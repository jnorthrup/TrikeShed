@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class MembershipKey {
    protected constructor()
    // TODO
    abstract fun isValid(): Boolean
    // TODO
    abstract fun drop(): Unit
    // TODO
    abstract fun block(sourceAddress: String): MembershipKey
    // TODO
    abstract fun unblock(sourceAddress: String): MembershipKey
    // TODO
    abstract fun channel(): MulticastChannel
    // TODO
    abstract fun group():CharSequence// TODO
    abstract fun networkInterface():CharSequence// TODO
    abstract fun sourceAddress():CharSequence}
