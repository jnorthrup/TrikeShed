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
    open fun openDatagramChannel(): DatagramChannel = TODO("NIO common stub")
    open fun openDatagramChannel(protocolFamily: String): DatagramChannel = TODO("NIO common stub")
    open fun openPipe(): Pipe = TODO("NIO common stub")
    open fun openSelector(): AbstractSelector = TODO("NIO common stub")
    open fun openServerSocketChannel(): ServerSocketChannel = TODO("NIO common stub")
    open fun openSocketChannel(): SocketChannel = TODO("NIO common stub")
    open fun inheritedChannel(): Channel = TODO("NIO common stub")
    open fun openSocketChannel(protocolFamily: String): SocketChannel = TODO("NIO common stub")
    open fun openServerSocketChannel(protocolFamily: String): ServerSocketChannel = TODO("NIO common stub")
    companion object {
        fun provider(): SelectorProvider = UringSelectorProvider
    }
}

internal object UringSelectorProvider : SelectorProvider() {
    override fun openDatagramChannel(): DatagramChannel = DatagramChannel.open()
    override fun openDatagramChannel(protocolFamily: String): DatagramChannel = DatagramChannel.open(protocolFamily)
    override fun openPipe(): Pipe = TODO("pipe")
    override fun openSelector(): AbstractSelector = TODO("selector")
    override fun openServerSocketChannel(): ServerSocketChannel = ServerSocketChannel.open()
    override fun openSocketChannel(): SocketChannel = SocketChannel.open()
    override fun inheritedChannel(): Channel = TODO("inheritedChannel")
    override fun openSocketChannel(protocolFamily: String): SocketChannel = SocketChannel.open()
    override fun openServerSocketChannel(protocolFamily: String): ServerSocketChannel = ServerSocketChannel.open()
}
