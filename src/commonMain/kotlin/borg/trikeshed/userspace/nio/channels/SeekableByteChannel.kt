@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface SeekableByteChannel : borg.trikeshed.userspace.nio.channels.ByteChannel {
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int = TODO("NIO common stub")
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int = TODO("NIO common stub")
    fun position(): Long = TODO("NIO common stub")
    fun position(p0: Long): borg.trikeshed.userspace.nio.channels.SeekableByteChannel = TODO("NIO common stub")
    fun size(): Long = TODO("NIO common stub")
    fun truncate(p0: Long): borg.trikeshed.userspace.nio.channels.SeekableByteChannel = TODO("NIO common stub")
}
