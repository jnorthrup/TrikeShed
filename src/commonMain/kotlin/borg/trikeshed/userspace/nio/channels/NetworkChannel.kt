@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface NetworkChannel : Channel {
    fun bind(address: String): NetworkChannel = TODO("NIO common stub")
    fun getLocalAddress(): String = TODO("NIO common stub")
    fun <T> setOption(option: String, value: T): NetworkChannel = TODO("NIO common stub")
    fun <T> getOption(option: String): T = TODO("NIO common stub")
    fun supportedOptions(): Set<String> = TODO("NIO common stub")
}
