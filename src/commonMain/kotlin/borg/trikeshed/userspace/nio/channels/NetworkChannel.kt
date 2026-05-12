@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface NetworkChannel : Channel {
    fun bind(address: String): NetworkChannel
    fun getLocalAddress():CharSequencefun <T> setOption(option: String, value: T): NetworkChannel
    fun <T> getOption(option: String): T
    fun supportedOptions(): Set<String>
}
