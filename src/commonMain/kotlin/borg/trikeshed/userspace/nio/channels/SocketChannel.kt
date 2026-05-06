@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Coerced to TrikeShed types.
public abstract class SocketChannel : AbstractSelectableChannel, ByteChannel, ScatteringByteChannel, GatheringByteChannel, NetworkChannel {
    protected constructor(provider: SelectorProvider) : super(provider)
    public abstract override fun validOps(): Int
    public abstract override fun bind(address: String): SocketChannel
    public abstract override fun <T> setOption(option: String, value: T): SocketChannel
    fun shutdownInput(): SocketChannel = TODO("NIO common stub")
    fun shutdownOutput(): SocketChannel = TODO("NIO common stub")
    fun isConnected(): Boolean = TODO("NIO common stub")
    fun isConnectionPending(): Boolean = TODO("NIO common stub")
    fun connect(address: String): Boolean = TODO("NIO common stub")
    fun finishConnect(): Boolean = TODO("NIO common stub")
    fun getRemoteAddress(): String = TODO("NIO common stub")
    public abstract override fun read(dst: ByteRegion): Int
    public abstract override fun read(dsts: Array<out ByteRegion>, offset: Int, length: Int): Long
    public abstract override fun read(dsts: Array<out ByteRegion>): Long
    public abstract override fun write(src: ByteSeries): Int
    public abstract override fun write(srcs: Array<out ByteSeries>, offset: Int, length: Int): Long
    public abstract override fun write(srcs: Array<out ByteSeries>): Long
    public abstract override fun getLocalAddress(): String

    companion object {
        fun `open`(): SocketChannel = TODO("NIO common stub")
        fun openWithProtocolFamily(protocolFamily: String): SocketChannel = TODO("NIO common stub")
        fun openWithRemote(address: String): SocketChannel = TODO("NIO common stub")
    }
}
