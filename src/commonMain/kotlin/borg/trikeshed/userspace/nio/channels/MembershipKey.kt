@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class MembershipKey {
    protected constructor()
    fun isValid(): Boolean = TODO("NIO common stub")
    fun drop(): Unit = TODO("NIO common stub")
    fun block(sourceAddress: String): MembershipKey = TODO("NIO common stub")
    fun unblock(sourceAddress: String): MembershipKey = TODO("NIO common stub")
    fun channel(): MulticastChannel = TODO("NIO common stub")
    fun group(): String = TODO("NIO common stub")
    fun networkInterface(): String = TODO("NIO common stub")
    fun sourceAddress(): String = TODO("NIO common stub")
}
