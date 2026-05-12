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
    // TODO
    abstract open fun openDatagramChannel(): DatagramChannel
    // TODO
    abstract open fun openDatagramChannel(protocolFamily: CharSequence): DatagramChannel
    // TODO
    abstract open fun openPipe(): Pipe
    // TODO
    abstract open fun openSelector(): AbstractSelector
    // TODO
    abstract open fun openServerSocketChannel(): ServerSocketChannel
    // TODO
    abstract open fun openSocketChannel(): SocketChannel
    // TODO
    abstract open fun inheritedChannel(): Channel
    // TODO
    abstract open fun openSocketChannel(protocolFamily: CharSequence): SocketChannel
    // TODO
    abstract open fun openServerSocketChannel(protocolFamily: CharSequence): ServerSocketChannel
    companion object {
        fun provider(): SelectorProvider = UringSelectorProvider
    }
}

internal object UringSelectorProvider : SelectorProvider() {
    override fun openDatagramChannel(): DatagramChannel = DatagramChannel.open()
    override fun openDatagramChannel(protocolFamily: CharSequence): DatagramChannel = DatagramChannel.open(protocolFamily)
    // TODO
    override fun openPipe(): Pipe = TODO("pipe")
    // TODO
    override fun openSelector(): AbstractSelector = TODO("selector")
    override fun openServerSocketChannel(): ServerSocketChannel = ServerSocketChannel.open()
    override fun openSocketChannel(): SocketChannel = SocketChannel.open()
    // TODO
    override fun inheritedChannel(): Channel = TODO("inheritedChannel")
    override fun openSocketChannel(protocolFamily: CharSequence): SocketChannel = SocketChannel.open()
    override fun openServerSocketChannel(protocolFamily: CharSequence): ServerSocketChannel = ServerSocketChannel.open()
}
