@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface NetworkChannel : borg.trikeshed.userspace.nio.channels.Channel {
    fun bind(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.NetworkChannel = TODO("NIO common stub")
    fun getLocalAddress(): java.net.SocketAddress = TODO("NIO common stub")
    fun <T> setOption(p0: java.net.SocketOption<T>, p1: T): borg.trikeshed.userspace.nio.channels.NetworkChannel = TODO("NIO common stub")
    fun <T> getOption(p0: java.net.SocketOption<T>): T = TODO("NIO common stub")
    fun supportedOptions(): java.util.Set<java.net.SocketOption<*>> = TODO("NIO common stub")
}
