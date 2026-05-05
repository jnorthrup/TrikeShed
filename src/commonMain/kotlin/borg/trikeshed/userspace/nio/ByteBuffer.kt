@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class ByteBuffer : borg.trikeshed.userspace.nio.Buffer, Comparable<borg.trikeshed.userspace.nio.ByteBuffer> {
    fun slice(): borg.trikeshed.userspace.nio.ByteBuffer
    fun slice(p0: Int, p1: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun duplicate(): borg.trikeshed.userspace.nio.ByteBuffer
    fun asReadOnlyBuffer(): borg.trikeshed.userspace.nio.ByteBuffer
    fun `get`(): Byte
    fun put(p0: Byte): borg.trikeshed.userspace.nio.ByteBuffer
    fun `get`(p0: Int): Byte
    fun put(p0: Int, p1: Byte): borg.trikeshed.userspace.nio.ByteBuffer
    fun `get`(p0: ByteArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun `get`(p0: ByteArray): borg.trikeshed.userspace.nio.ByteBuffer
    fun `get`(p0: Int, p1: ByteArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun `get`(p0: Int, p1: ByteArray): borg.trikeshed.userspace.nio.ByteBuffer
    fun put(p0: borg.trikeshed.userspace.nio.ByteBuffer): borg.trikeshed.userspace.nio.ByteBuffer
    fun put(p0: Int, p1: borg.trikeshed.userspace.nio.ByteBuffer, p2: Int, p3: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun put(p0: ByteArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun put(p0: ByteArray): borg.trikeshed.userspace.nio.ByteBuffer
    fun put(p0: Int, p1: ByteArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun put(p0: Int, p1: ByteArray): borg.trikeshed.userspace.nio.ByteBuffer
    fun hasArray(): Boolean
    fun array(): ByteArray
    fun arrayOffset(): Int
    fun position(p0: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun limit(p0: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun mark(): borg.trikeshed.userspace.nio.ByteBuffer
    fun reset(): borg.trikeshed.userspace.nio.ByteBuffer
    fun clear(): borg.trikeshed.userspace.nio.ByteBuffer
    fun flip(): borg.trikeshed.userspace.nio.ByteBuffer
    fun rewind(): borg.trikeshed.userspace.nio.ByteBuffer
    fun compact(): borg.trikeshed.userspace.nio.ByteBuffer
    fun isDirect(): Boolean
    override fun toString(): String
    override fun hashCode(): Int
    override fun equals(p0: Any?): Boolean
    override fun compareTo(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun mismatch(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun order(): borg.trikeshed.userspace.nio.ByteOrder
    fun order(p0: borg.trikeshed.userspace.nio.ByteOrder): borg.trikeshed.userspace.nio.ByteBuffer
    fun alignmentOffset(p0: Int, p1: Int): Int
    fun alignedSlice(p0: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun getChar(): Char
    fun putChar(p0: Char): borg.trikeshed.userspace.nio.ByteBuffer
    fun getChar(p0: Int): Char
    fun putChar(p0: Int, p1: Char): borg.trikeshed.userspace.nio.ByteBuffer
    fun asCharBuffer(): borg.trikeshed.userspace.nio.CharBuffer
    fun getShort(): Short
    fun putShort(p0: Short): borg.trikeshed.userspace.nio.ByteBuffer
    fun getShort(p0: Int): Short
    fun putShort(p0: Int, p1: Short): borg.trikeshed.userspace.nio.ByteBuffer
    fun asShortBuffer(): borg.trikeshed.userspace.nio.ShortBuffer
    fun getInt(): Int
    fun putInt(p0: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun getInt(p0: Int): Int
    fun putInt(p0: Int, p1: Int): borg.trikeshed.userspace.nio.ByteBuffer
    fun asIntBuffer(): borg.trikeshed.userspace.nio.IntBuffer
    fun getLong(): Long
    fun putLong(p0: Long): borg.trikeshed.userspace.nio.ByteBuffer
    fun getLong(p0: Int): Long
    fun putLong(p0: Int, p1: Long): borg.trikeshed.userspace.nio.ByteBuffer
    fun asLongBuffer(): borg.trikeshed.userspace.nio.LongBuffer
    fun getFloat(): Float
    fun putFloat(p0: Float): borg.trikeshed.userspace.nio.ByteBuffer
    fun getFloat(p0: Int): Float
    fun putFloat(p0: Int, p1: Float): borg.trikeshed.userspace.nio.ByteBuffer
    fun asFloatBuffer(): borg.trikeshed.userspace.nio.FloatBuffer
    fun getDouble(): Double
    fun putDouble(p0: Double): borg.trikeshed.userspace.nio.ByteBuffer
    fun getDouble(p0: Int): Double
    fun putDouble(p0: Int, p1: Double): borg.trikeshed.userspace.nio.ByteBuffer
    fun asDoubleBuffer(): borg.trikeshed.userspace.nio.DoubleBuffer
    override fun compareTo(p0: Any): Int
    companion object {
        fun allocateDirect(p0: Int): borg.trikeshed.userspace.nio.ByteBuffer
        fun allocate(p0: Int): borg.trikeshed.userspace.nio.ByteBuffer
        fun wrap(p0: ByteArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.ByteBuffer
        fun wrap(p0: ByteArray): borg.trikeshed.userspace.nio.ByteBuffer
    }
}
