@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class LongBuffer : borg.trikeshed.userspace.nio.Buffer, Comparable<borg.trikeshed.userspace.nio.LongBuffer> {
    fun slice(): borg.trikeshed.userspace.nio.LongBuffer
    fun slice(p0: Int, p1: Int): borg.trikeshed.userspace.nio.LongBuffer
    fun duplicate(): borg.trikeshed.userspace.nio.LongBuffer
    fun asReadOnlyBuffer(): borg.trikeshed.userspace.nio.LongBuffer
    fun `get`(): Long
    fun put(p0: Long): borg.trikeshed.userspace.nio.LongBuffer
    fun `get`(p0: Int): Long
    fun put(p0: Int, p1: Long): borg.trikeshed.userspace.nio.LongBuffer
    fun `get`(p0: LongArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.LongBuffer
    fun `get`(p0: LongArray): borg.trikeshed.userspace.nio.LongBuffer
    fun `get`(p0: Int, p1: LongArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.LongBuffer
    fun `get`(p0: Int, p1: LongArray): borg.trikeshed.userspace.nio.LongBuffer
    fun put(p0: borg.trikeshed.userspace.nio.LongBuffer): borg.trikeshed.userspace.nio.LongBuffer
    fun put(p0: Int, p1: borg.trikeshed.userspace.nio.LongBuffer, p2: Int, p3: Int): borg.trikeshed.userspace.nio.LongBuffer
    fun put(p0: LongArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.LongBuffer
    fun put(p0: LongArray): borg.trikeshed.userspace.nio.LongBuffer
    fun put(p0: Int, p1: LongArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.LongBuffer
    fun put(p0: Int, p1: LongArray): borg.trikeshed.userspace.nio.LongBuffer
    fun hasArray(): Boolean
    fun array(): LongArray
    fun arrayOffset(): Int
    fun position(p0: Int): borg.trikeshed.userspace.nio.LongBuffer
    fun limit(p0: Int): borg.trikeshed.userspace.nio.LongBuffer
    fun mark(): borg.trikeshed.userspace.nio.LongBuffer
    fun reset(): borg.trikeshed.userspace.nio.LongBuffer
    fun clear(): borg.trikeshed.userspace.nio.LongBuffer
    fun flip(): borg.trikeshed.userspace.nio.LongBuffer
    fun rewind(): borg.trikeshed.userspace.nio.LongBuffer
    fun compact(): borg.trikeshed.userspace.nio.LongBuffer
    fun isDirect(): Boolean
    override fun toString(): String
    override fun hashCode(): Int
    override fun equals(p0: Any?): Boolean
    override fun compareTo(p0: borg.trikeshed.userspace.nio.LongBuffer): Int
    fun mismatch(p0: borg.trikeshed.userspace.nio.LongBuffer): Int
    fun order(): borg.trikeshed.userspace.nio.ByteOrder
    override fun compareTo(p0: Any): Int
    companion object {
        fun allocate(p0: Int): borg.trikeshed.userspace.nio.LongBuffer
        fun wrap(p0: LongArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.LongBuffer
        fun wrap(p0: LongArray): borg.trikeshed.userspace.nio.LongBuffer
    }
}
