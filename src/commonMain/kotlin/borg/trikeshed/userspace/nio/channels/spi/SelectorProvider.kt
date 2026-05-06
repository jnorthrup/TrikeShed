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
    protected constructor()
    fun openDatagramChannel(): DatagramChannel = TODO("NIO common stub")
    fun openDatagramChannel(protocolFamily: String): DatagramChannel = TODO("NIO common stub")
    fun openPipe(): Pipe = TODO("NIO common stub")
    fun openSelector(): AbstractSelector = TODO("NIO common stub")
    fun openServerSocketChannel(): ServerSocketChannel = TODO("NIO common stub")
    fun openSocketChannel(): SocketChannel = TODO("NIO common stub")
    fun inheritedChannel(): Channel = TODO("NIO common stub")
    fun openSocketChannel(protocolFamily: String): SocketChannel = TODO("NIO common stub")
    fun openServerSocketChannel(protocolFamily: String): ServerSocketChannel = TODO("NIO common stub")
    companion object {
        fun provider(): SelectorProvider = TODO("NIO common stub")
    }
}
