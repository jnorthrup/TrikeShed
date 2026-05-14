@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.ByteBuffer

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface SeekableByteChannel : ByteChannel {
    override fun read(dst: ByteBuffer): Int
    override fun write(src: ByteBuffer): Int
    fun position(): Long
    fun position(newPosition: Long): SeekableByteChannel
    fun size(): Long
    fun truncate(size: Long): SeekableByteChannel
}
