@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class DatagramChannel : AbstractSelectableChannel, ByteChannel, ScatteringByteChannel, GatheringByteChannel, MulticastChannel {
    protected constructor(provider: SelectorProvider) : super(provider)
    public abstract override fun close()
    public abstract override fun validOps(): Int
    public abstract override fun bind(address: String): DatagramChannel
    public abstract override fun <T> setOption(option: String, value: T): DatagramChannel
    fun isConnected(): Boolean = TODO("NIO common stub")
    fun connect(address: String): DatagramChannel = TODO("NIO common stub")
    fun disconnect(): DatagramChannel = TODO("NIO common stub")
    fun getRemoteAddress(): String = TODO("NIO common stub")
    fun receive(dst: ByteRegion): String = TODO("NIO common stub")
    fun send(src: ByteSeries, address: String): Int = TODO("NIO common stub")
    public abstract override fun read(dst: ByteRegion): Int
    public abstract override fun read(dsts: Array<out ByteRegion>, offset: Int, length: Int): Long
    public abstract override fun read(dsts: Array<out ByteRegion>): Long
    public abstract override fun write(src: ByteSeries): Int
    public abstract override fun write(srcs: Array<out ByteSeries>, offset: Int, length: Int): Long
    public abstract override fun write(srcs: Array<out ByteSeries>): Long
    public abstract override fun getLocalAddress(): String

    companion object {
        fun `open`(): DatagramChannel = TODO("NIO common stub")
        fun `open`(protocolFamily: String): DatagramChannel = TODO("NIO common stub")
    }
}
