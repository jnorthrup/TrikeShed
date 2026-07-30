@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.channels.DatagramChannel
import borg.trikeshed.userspace.nio.channels.Pipe
import borg.trikeshed.userspace.nio.channels.ServerSocketChannel
import borg.trikeshed.userspace.nio.channels.SocketChannel
import borg.trikeshed.userspace.nio.channels.Channel

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class SelectorProvider {
    constructor()
    abstract fun openDatagramChannel(): DatagramChannel
    abstract fun openDatagramChannel(protocolFamily: String): DatagramChannel
    abstract fun openPipe(): Pipe
    abstract fun openSelector(): AbstractSelector
    abstract fun openServerSocketChannel(): ServerSocketChannel
    abstract fun openSocketChannel(): SocketChannel
    abstract fun inheritedChannel(): Channel
    abstract fun openSocketChannel(protocolFamily: String): SocketChannel
    abstract fun openServerSocketChannel(protocolFamily: String): ServerSocketChannel
    companion object {
        fun provider(): SelectorProvider = UringSelectorProvider
    }
}

internal object UringSelectorProvider : SelectorProvider() {
    override fun openDatagramChannel(): DatagramChannel = DatagramChannel.open()
    override fun openDatagramChannel(protocolFamily: String): DatagramChannel = DatagramChannel.open(protocolFamily)
    override fun openPipe(): Pipe = throw UnsupportedOperationException("pipe")
    override fun openSelector(): AbstractSelector = throw UnsupportedOperationException("selector")
    override fun openServerSocketChannel(): ServerSocketChannel = ServerSocketChannel.open()
    override fun openSocketChannel(): SocketChannel = SocketChannel.open()
    override fun inheritedChannel(): Channel = throw UnsupportedOperationException("inheritedChannel")
    override fun openSocketChannel(protocolFamily: String): SocketChannel = SocketChannel.open()
    override fun openServerSocketChannel(protocolFamily: String): ServerSocketChannel = ServerSocketChannel.open()
}
