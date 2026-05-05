@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class ShortBuffer : borg.trikeshed.userspace.nio.Buffer, Comparable<borg.trikeshed.userspace.nio.ShortBuffer> {
    fun slice(): borg.trikeshed.userspace.nio.ShortBuffer
    fun slice(p0: Int, p1: Int): borg.trikeshed.userspace.nio.ShortBuffer
    fun duplicate(): borg.trikeshed.userspace.nio.ShortBuffer
    fun asReadOnlyBuffer(): borg.trikeshed.userspace.nio.ShortBuffer
    fun `get`(): Short
    fun put(p0: Short): borg.trikeshed.userspace.nio.ShortBuffer
    fun `get`(p0: Int): Short
    fun put(p0: Int, p1: Short): borg.trikeshed.userspace.nio.ShortBuffer
    fun `get`(p0: ShortArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.ShortBuffer
    fun `get`(p0: ShortArray): borg.trikeshed.userspace.nio.ShortBuffer
    fun `get`(p0: Int, p1: ShortArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.ShortBuffer
    fun `get`(p0: Int, p1: ShortArray): borg.trikeshed.userspace.nio.ShortBuffer
    fun put(p0: borg.trikeshed.userspace.nio.ShortBuffer): borg.trikeshed.userspace.nio.ShortBuffer
    fun put(p0: Int, p1: borg.trikeshed.userspace.nio.ShortBuffer, p2: Int, p3: Int): borg.trikeshed.userspace.nio.ShortBuffer
    fun put(p0: ShortArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.ShortBuffer
    fun put(p0: ShortArray): borg.trikeshed.userspace.nio.ShortBuffer
    fun put(p0: Int, p1: ShortArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.ShortBuffer
    fun put(p0: Int, p1: ShortArray): borg.trikeshed.userspace.nio.ShortBuffer
    fun hasArray(): Boolean
    fun array(): ShortArray
    fun arrayOffset(): Int
    fun position(p0: Int): borg.trikeshed.userspace.nio.ShortBuffer
    fun limit(p0: Int): borg.trikeshed.userspace.nio.ShortBuffer
    fun mark(): borg.trikeshed.userspace.nio.ShortBuffer
    fun reset(): borg.trikeshed.userspace.nio.ShortBuffer
    fun clear(): borg.trikeshed.userspace.nio.ShortBuffer
    fun flip(): borg.trikeshed.userspace.nio.ShortBuffer
    fun rewind(): borg.trikeshed.userspace.nio.ShortBuffer
    fun compact(): borg.trikeshed.userspace.nio.ShortBuffer
    fun isDirect(): Boolean
    override fun toString(): String
    override fun hashCode(): Int
    override fun equals(p0: Any?): Boolean
    override fun compareTo(p0: borg.trikeshed.userspace.nio.ShortBuffer): Int
    fun mismatch(p0: borg.trikeshed.userspace.nio.ShortBuffer): Int
    fun order(): borg.trikeshed.userspace.nio.ByteOrder
    override fun compareTo(p0: Any): Int
    companion object {
        fun allocate(p0: Int): borg.trikeshed.userspace.nio.ShortBuffer
        fun wrap(p0: ShortArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.ShortBuffer
        fun wrap(p0: ShortArray): borg.trikeshed.userspace.nio.ShortBuffer
    }
}
