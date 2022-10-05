package borg.trikeshed.lib.collections

object _a {
    inline operator fun get(vararg t: Boolean): BooleanArray = t
    inline operator fun get(vararg t: Byte): ByteArray = t
    inline operator fun get(vararg t: UByte): UByteArray = t
    inline operator fun get(vararg t: Char): CharArray = t
    inline operator fun get(vararg t: Short): ShortArray = t
    inline operator fun get(vararg t: UShort): UShortArray = t
    inline operator fun get(vararg t: Int): IntArray = t
    inline operator fun get(vararg t: UInt): UIntArray = t
    inline operator fun get(vararg t: Long): LongArray = t
    inline operator fun get(vararg t: ULong): ULongArray = t
    inline operator fun get(vararg t: Float): FloatArray = t
    inline operator fun get(vararg t: Double): DoubleArray = t
    inline operator fun <T> get(vararg t: T): Array<out T> =t
}