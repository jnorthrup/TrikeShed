@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect interface SeekableByteChannel : borg.trikeshed.userspace.nio.channels.ByteChannel {
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun position(): Long
    fun position(p0: Long): borg.trikeshed.userspace.nio.channels.SeekableByteChannel
    fun size(): Long
    fun truncate(p0: Long): borg.trikeshed.userspace.nio.channels.SeekableByteChannel
}
