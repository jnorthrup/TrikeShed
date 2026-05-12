@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface NetworkChannel : Channel {
    fun bind(address: CharSequence): NetworkChannel
    fun getLocalAddress(): CharSequence
    fun <T> setOption(option: CharSequence, value: T): NetworkChannel
    fun <T> getOption(option: CharSequence): T
    fun supportedOptions(): Set<CharSequence>
}
