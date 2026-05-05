@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class DoubleBuffer : borg.trikeshed.userspace.nio.Buffer, Comparable<borg.trikeshed.userspace.nio.DoubleBuffer> {
    fun slice(): borg.trikeshed.userspace.nio.DoubleBuffer
    fun slice(p0: Int, p1: Int): borg.trikeshed.userspace.nio.DoubleBuffer
    fun duplicate(): borg.trikeshed.userspace.nio.DoubleBuffer
    fun asReadOnlyBuffer(): borg.trikeshed.userspace.nio.DoubleBuffer
    fun `get`(): Double
    fun put(p0: Double): borg.trikeshed.userspace.nio.DoubleBuffer
    fun `get`(p0: Int): Double
    fun put(p0: Int, p1: Double): borg.trikeshed.userspace.nio.DoubleBuffer
    fun `get`(p0: DoubleArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.DoubleBuffer
    fun `get`(p0: DoubleArray): borg.trikeshed.userspace.nio.DoubleBuffer
    fun `get`(p0: Int, p1: DoubleArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.DoubleBuffer
    fun `get`(p0: Int, p1: DoubleArray): borg.trikeshed.userspace.nio.DoubleBuffer
    fun put(p0: borg.trikeshed.userspace.nio.DoubleBuffer): borg.trikeshed.userspace.nio.DoubleBuffer
    fun put(p0: Int, p1: borg.trikeshed.userspace.nio.DoubleBuffer, p2: Int, p3: Int): borg.trikeshed.userspace.nio.DoubleBuffer
    fun put(p0: DoubleArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.DoubleBuffer
    fun put(p0: DoubleArray): borg.trikeshed.userspace.nio.DoubleBuffer
    fun put(p0: Int, p1: DoubleArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.DoubleBuffer
    fun put(p0: Int, p1: DoubleArray): borg.trikeshed.userspace.nio.DoubleBuffer
    fun hasArray(): Boolean
    fun array(): DoubleArray
    fun arrayOffset(): Int
    fun position(p0: Int): borg.trikeshed.userspace.nio.DoubleBuffer
    fun limit(p0: Int): borg.trikeshed.userspace.nio.DoubleBuffer
    fun mark(): borg.trikeshed.userspace.nio.DoubleBuffer
    fun reset(): borg.trikeshed.userspace.nio.DoubleBuffer
    fun clear(): borg.trikeshed.userspace.nio.DoubleBuffer
    fun flip(): borg.trikeshed.userspace.nio.DoubleBuffer
    fun rewind(): borg.trikeshed.userspace.nio.DoubleBuffer
    fun compact(): borg.trikeshed.userspace.nio.DoubleBuffer
    fun isDirect(): Boolean
    override fun toString(): String
    override fun hashCode(): Int
    override fun equals(p0: Any?): Boolean
    override fun compareTo(p0: borg.trikeshed.userspace.nio.DoubleBuffer): Int
    fun mismatch(p0: borg.trikeshed.userspace.nio.DoubleBuffer): Int
    fun order(): borg.trikeshed.userspace.nio.ByteOrder
    override fun compareTo(p0: Any): Int
    companion object {
        fun allocate(p0: Int): borg.trikeshed.userspace.nio.DoubleBuffer
        fun wrap(p0: DoubleArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.DoubleBuffer
        fun wrap(p0: DoubleArray): borg.trikeshed.userspace.nio.DoubleBuffer
    }
}
