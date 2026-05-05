@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect interface MulticastChannel : borg.trikeshed.userspace.nio.channels.NetworkChannel {
    fun close(): Unit
    fun join(p0: java.net.InetAddress, p1: java.net.NetworkInterface): borg.trikeshed.userspace.nio.channels.MembershipKey
    fun join(p0: java.net.InetAddress, p1: java.net.NetworkInterface, p2: java.net.InetAddress): borg.trikeshed.userspace.nio.channels.MembershipKey
}
