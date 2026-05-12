@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class DatagramChannel : AbstractSelectableChannel, ByteChannel, ScatteringByteChannel, GatheringByteChannel, MulticastChannel {
    protected constructor(provider: SelectorProvider) : super(provider)
    public abstract override fun close()
    public abstract override fun validOps(): Int
    public abstract override fun bind(address: CharSequence): DatagramChannel
    public abstract override fun <T> setOption(option: CharSequence, value: T): DatagramChannel
    // TODO
    abstract fun isConnected(): Boolean
    // TODO
    abstract fun connect(address: CharSequence): DatagramChannel
    // TODO
    abstract fun disconnect(): DatagramChannel
    // TODO
    abstract fun getRemoteAddress():CharSequence// TODO
    abstract fun receive(dst: ByteRegion):CharSequence// TODO
    abstract fun send(src: ByteBuffer, address: CharSequence): Int
    public abstract override fun read(dst: ByteBuffer): Int
    public abstract override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun read(dsts: Array<out ByteBuffer>): Long
    public abstract override fun write(src: ByteBuffer): Int
    public abstract override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun write(srcs: Array<out ByteBuffer>): Long
    public abstract override fun getLocalAddress(): CharSequence
    companion object {
        fun `open`(): DatagramChannel = TODO("NIO common stub")
        fun `open`(protocolFamily: CharSequence): DatagramChannel = TODO("NIO common stub")
    }
}
