@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface MulticastChannel : NetworkChannel {
    override fun close(): Unit
    fun join(group: String, networkInterface: String): MembershipKey
    fun join(group: String, networkInterface: String, source: String): MembershipKey
}
