@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect interface NetworkChannel : borg.trikeshed.userspace.nio.channels.Channel {
    fun bind(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.NetworkChannel
    fun getLocalAddress(): java.net.SocketAddress
    fun <T> setOption(p0: java.net.SocketOption<T>, p1: T): borg.trikeshed.userspace.nio.channels.NetworkChannel
    fun <T> getOption(p0: java.net.SocketOption<T>): T
    fun supportedOptions(): java.util.Set<java.net.SocketOption<*>>
}
