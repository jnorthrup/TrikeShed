@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class ByteBuffer : Buffer(), Comparable<ByteBuffer> {
override fun : ByteBuffer
    fun slice(p0: Int, p1: Int): ByteBuffer
    fun duplicate(): ByteBuffer
    fun asReadOnlyBuffer(): ByteBuffer
    fun `get`(): Byte
    fun put(p0: Byte): ByteBuffer
    fun `get`(p0: Int): Byte
    fun put(p0: Int, p1: Byte): ByteBuffer
    fun `get`(p0: ByteArray, p1: Int, p2: Int): ByteBuffer
    fun `get`(p0: ByteArray): ByteBuffer
    fun `get`(p0: Int, p1: ByteArray, p2: Int, p3: Int): ByteBuffer
    fun `get`(p0: Int, p1: ByteArray): ByteBuffer
    fun put(p0: ByteBuffer): ByteBuffer
    fun put(p0: Int, p1: ByteBuffer, p2: Int, p3: Int): ByteBuffer
    fun put(p0: ByteArray, p1: Int, p2: Int): ByteBuffer
    fun put(p0: ByteArray): ByteBuffer
    fun put(p0: Int, p1: ByteArray, p2: Int, p3: Int): ByteBuffer
    fun put(p0: Int, p1: ByteArray): ByteBuffer
    fun hasArray(): Boolean
    fun array(): ByteArray
    fun arrayOffset(): Int
    fun position(p0: Int): ByteBuffer
    fun limit(p0: Int): ByteBuffer
    fun mark(): ByteBuffer
    fun reset(): ByteBuffer
    fun clear(): ByteBuffer
    fun flip(): ByteBuffer
    fun rewind(): ByteBuffer
    fun compact(): ByteBuffer
    fun isDirect(): Boolean
    override fun toString(): String
    override fun hashCode(): Int
    override fun equals(p0: Any?): Boolean
    override fun compareTo(p0: ByteBuffer): Int
    fun mismatch(p0: ByteBuffer): Int
    fun order(): ByteOrder
    fun order(p0: ByteOrder): ByteBuffer
    fun alignmentOffset(p0: Int, p1: Int): Int
    fun alignedSlice(p0: Int): ByteBuffer
    fun getChar(): Char
    fun putChar(p0: Char): ByteBuffer
    fun getChar(p0: Int): Char
    fun putChar(p0: Int, p1: Char): ByteBuffer
    fun asCharBuffer(): CharBuffer
    fun getShort(): Short
    fun putShort(p0: Short): ByteBuffer
    fun getShort(p0: Int): Short
    fun putShort(p0: Int, p1: Short): ByteBuffer
    fun asShortBuffer(): ShortBuffer
    fun getInt(): Int
    fun putInt(p0: Int): ByteBuffer
    fun getInt(p0: Int): Int
    fun putInt(p0: Int, p1: Int): ByteBuffer
    fun asIntBuffer(): IntBuffer
    fun getLong(): Long
    fun putLong(p0: Long): ByteBuffer
    fun getLong(p0: Int): Long
    fun putLong(p0: Int, p1: Long): ByteBuffer
    fun asLongBuffer(): LongBuffer
    fun getFloat(): Float
    fun putFloat(p0: Float): ByteBuffer
    fun getFloat(p0: Int): Float
    fun putFloat(p0: Int, p1: Float): ByteBuffer
    fun asFloatBuffer(): FloatBuffer
    fun getDouble(): Double
    fun putDouble(p0: Double): ByteBuffer
    fun getDouble(p0: Int): Double
    fun putDouble(p0: Int, p1: Double): ByteBuffer
    fun asDoubleBuffer(): DoubleBuffer
    override fun compareTo(p0: Any): Int
    companion object {
        fun allocateDirect(p0: Int): ByteBuffer
        fun allocate(p0: Int): ByteBuffer
        fun wrap(p0: ByteArray, p1: Int, p2: Int): ByteBuffer
        fun wrap(p0: ByteArray): ByteBuffer
    }
}
