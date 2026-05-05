@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class MembershipKey {
    protected constructor()
    fun isValid(): Boolean
    fun drop(): Unit
    fun block(p0: java.net.InetAddress): borg.trikeshed.userspace.nio.channels.MembershipKey
    fun unblock(p0: java.net.InetAddress): borg.trikeshed.userspace.nio.channels.MembershipKey
    fun channel(): borg.trikeshed.userspace.nio.channels.MulticastChannel
    fun group(): java.net.InetAddress
    fun networkInterface(): java.net.NetworkInterface
    fun sourceAddress(): java.net.InetAddress
}
