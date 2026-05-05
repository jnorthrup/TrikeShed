@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class SelectorProvider {
    protected constructor()
    fun openDatagramChannel(): borg.trikeshed.userspace.nio.channels.DatagramChannel
    fun openDatagramChannel(p0: java.net.ProtocolFamily): borg.trikeshed.userspace.nio.channels.DatagramChannel
    fun openPipe(): borg.trikeshed.userspace.nio.channels.Pipe
    fun openSelector(): borg.trikeshed.userspace.nio.channels.spi.AbstractSelector
    fun openServerSocketChannel(): borg.trikeshed.userspace.nio.channels.ServerSocketChannel
    fun openSocketChannel(): borg.trikeshed.userspace.nio.channels.SocketChannel
    fun inheritedChannel(): borg.trikeshed.userspace.nio.channels.Channel
    fun openSocketChannel(p0: java.net.ProtocolFamily): borg.trikeshed.userspace.nio.channels.SocketChannel
    fun openServerSocketChannel(p0: java.net.ProtocolFamily): borg.trikeshed.userspace.nio.channels.ServerSocketChannel
    companion object {
        fun provider(): borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
    }
}
