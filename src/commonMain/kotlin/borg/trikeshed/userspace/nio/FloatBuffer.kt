@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FloatBuffer : borg.trikeshed.userspace.nio.Buffer, Comparable<borg.trikeshed.userspace.nio.FloatBuffer> {
    fun slice(): borg.trikeshed.userspace.nio.FloatBuffer
    fun slice(p0: Int, p1: Int): borg.trikeshed.userspace.nio.FloatBuffer
    fun duplicate(): borg.trikeshed.userspace.nio.FloatBuffer
    fun asReadOnlyBuffer(): borg.trikeshed.userspace.nio.FloatBuffer
    fun `get`(): Float
    fun put(p0: Float): borg.trikeshed.userspace.nio.FloatBuffer
    fun `get`(p0: Int): Float
    fun put(p0: Int, p1: Float): borg.trikeshed.userspace.nio.FloatBuffer
    fun `get`(p0: FloatArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.FloatBuffer
    fun `get`(p0: FloatArray): borg.trikeshed.userspace.nio.FloatBuffer
    fun `get`(p0: Int, p1: FloatArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.FloatBuffer
    fun `get`(p0: Int, p1: FloatArray): borg.trikeshed.userspace.nio.FloatBuffer
    fun put(p0: borg.trikeshed.userspace.nio.FloatBuffer): borg.trikeshed.userspace.nio.FloatBuffer
    fun put(p0: Int, p1: borg.trikeshed.userspace.nio.FloatBuffer, p2: Int, p3: Int): borg.trikeshed.userspace.nio.FloatBuffer
    fun put(p0: FloatArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.FloatBuffer
    fun put(p0: FloatArray): borg.trikeshed.userspace.nio.FloatBuffer
    fun put(p0: Int, p1: FloatArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.FloatBuffer
    fun put(p0: Int, p1: FloatArray): borg.trikeshed.userspace.nio.FloatBuffer
    fun hasArray(): Boolean
    fun array(): FloatArray
    fun arrayOffset(): Int
    fun position(p0: Int): borg.trikeshed.userspace.nio.FloatBuffer
    fun limit(p0: Int): borg.trikeshed.userspace.nio.FloatBuffer
    fun mark(): borg.trikeshed.userspace.nio.FloatBuffer
    fun reset(): borg.trikeshed.userspace.nio.FloatBuffer
    fun clear(): borg.trikeshed.userspace.nio.FloatBuffer
    fun flip(): borg.trikeshed.userspace.nio.FloatBuffer
    fun rewind(): borg.trikeshed.userspace.nio.FloatBuffer
    fun compact(): borg.trikeshed.userspace.nio.FloatBuffer
    fun isDirect(): Boolean
    override fun toString(): String
    override fun hashCode(): Int
    override fun equals(p0: Any?): Boolean
    override fun compareTo(p0: borg.trikeshed.userspace.nio.FloatBuffer): Int
    fun mismatch(p0: borg.trikeshed.userspace.nio.FloatBuffer): Int
    fun order(): borg.trikeshed.userspace.nio.ByteOrder
    override fun compareTo(p0: Any): Int
    companion object {
        fun allocate(p0: Int): borg.trikeshed.userspace.nio.FloatBuffer
        fun wrap(p0: FloatArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.FloatBuffer
        fun wrap(p0: FloatArray): borg.trikeshed.userspace.nio.FloatBuffer
    }
}
