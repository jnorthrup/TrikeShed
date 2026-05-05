@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class MappedByteBuffer : borg.trikeshed.userspace.nio.ByteBuffer {
    fun isLoaded(): Boolean
    fun load(): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun force(): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun force(p0: Int, p1: Int): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun position(p0: Int): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun limit(p0: Int): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun mark(): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun reset(): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun clear(): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun flip(): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun rewind(): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun slice(): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun slice(p0: Int, p1: Int): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun duplicate(): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun compact(): borg.trikeshed.userspace.nio.MappedByteBuffer
}
