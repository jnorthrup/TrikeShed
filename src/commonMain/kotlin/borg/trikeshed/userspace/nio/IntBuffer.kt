@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class IntBuffer : borg.trikeshed.userspace.nio.Buffer, Comparable<borg.trikeshed.userspace.nio.IntBuffer> {
    fun slice(): borg.trikeshed.userspace.nio.IntBuffer
    fun slice(p0: Int, p1: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun duplicate(): borg.trikeshed.userspace.nio.IntBuffer
    fun asReadOnlyBuffer(): borg.trikeshed.userspace.nio.IntBuffer
    fun `get`(): Int
    fun put(p0: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun `get`(p0: Int): Int
    fun put(p0: Int, p1: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun `get`(p0: IntArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun `get`(p0: IntArray): borg.trikeshed.userspace.nio.IntBuffer
    fun `get`(p0: Int, p1: IntArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun `get`(p0: Int, p1: IntArray): borg.trikeshed.userspace.nio.IntBuffer
    fun put(p0: borg.trikeshed.userspace.nio.IntBuffer): borg.trikeshed.userspace.nio.IntBuffer
    fun put(p0: Int, p1: borg.trikeshed.userspace.nio.IntBuffer, p2: Int, p3: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun put(p0: IntArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun put(p0: IntArray): borg.trikeshed.userspace.nio.IntBuffer
    fun put(p0: Int, p1: IntArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun put(p0: Int, p1: IntArray): borg.trikeshed.userspace.nio.IntBuffer
    fun hasArray(): Boolean
    fun array(): IntArray
    fun arrayOffset(): Int
    fun position(p0: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun limit(p0: Int): borg.trikeshed.userspace.nio.IntBuffer
    fun mark(): borg.trikeshed.userspace.nio.IntBuffer
    fun reset(): borg.trikeshed.userspace.nio.IntBuffer
    fun clear(): borg.trikeshed.userspace.nio.IntBuffer
    fun flip(): borg.trikeshed.userspace.nio.IntBuffer
    fun rewind(): borg.trikeshed.userspace.nio.IntBuffer
    fun compact(): borg.trikeshed.userspace.nio.IntBuffer
    fun isDirect(): Boolean
    override fun toString(): String
    override fun hashCode(): Int
    override fun equals(p0: Any?): Boolean
    override fun compareTo(p0: borg.trikeshed.userspace.nio.IntBuffer): Int
    fun mismatch(p0: borg.trikeshed.userspace.nio.IntBuffer): Int
    fun order(): borg.trikeshed.userspace.nio.ByteOrder
    override fun compareTo(p0: Any): Int
    companion object {
        fun allocate(p0: Int): borg.trikeshed.userspace.nio.IntBuffer
        fun wrap(p0: IntArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.IntBuffer
        fun wrap(p0: IntArray): borg.trikeshed.userspace.nio.IntBuffer
    }
}
