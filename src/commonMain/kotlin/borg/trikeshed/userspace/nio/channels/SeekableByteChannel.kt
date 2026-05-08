@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.nio.ByteBuffer

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface SeekableByteChannel : ByteChannel {
    override fun read(dst: ByteRegion): Int = TODO("NIO common stub")
    override fun write(src: ByteBuffer): Int = TODO("NIO common stub")
    fun position(): Long = TODO("NIO common stub")
    fun position(newPosition: Long): SeekableByteChannel = TODO("NIO common stub")
    fun size(): Long = TODO("NIO common stub")
    fun truncate(size: Long): SeekableByteChannel = TODO("NIO common stub")
}
