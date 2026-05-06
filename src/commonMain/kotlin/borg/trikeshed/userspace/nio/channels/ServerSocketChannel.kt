@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class ServerSocketChannel : AbstractSelectableChannel, NetworkChannel {
    protected constructor(provider: SelectorProvider) : super(provider)
    public abstract override fun validOps(): Int
    public abstract override fun bind(address: String): ServerSocketChannel
    fun bind(address: String, backlog: Int): ServerSocketChannel = TODO("NIO common stub")
    public abstract override fun <T> setOption(option: String, value: T): ServerSocketChannel
    fun accept(): SocketChannel = TODO("NIO common stub")
    public abstract override fun getLocalAddress(): String

    companion object {
        fun `open`(): ServerSocketChannel = TODO("NIO common stub")
        fun `open`(protocolFamily: String): ServerSocketChannel = TODO("NIO common stub")
    }
}
