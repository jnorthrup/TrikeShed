package borg.trikeshed.lib

import kotlin.jvm.JvmInline

// The runtime probe stays at construction, never in a packed field accessor.
// Long and Double pairs exceed 64 bits; unsigned value classes and references
// keep their original representation.
@PublishedApi
internal fun packedPrimitiveJoin(a: Any?, b: Any?): Join<*, *>? = when (a) {
    is Boolean -> when (b) {
        is Boolean -> a j b
        is Byte -> a j b
        is Short -> a j b
        is Char -> a j b
        is Int -> a j b
        is Float -> a j b
        else -> null
    }
    is Byte -> when (b) {
        is Boolean -> a j b
        is Byte -> a j b
        is Short -> a j b
        is Char -> a j b
        is Int -> a j b
        is Float -> a j b
        else -> null
    }
    is Short -> when (b) {
        is Boolean -> a j b
        is Byte -> a j b
        is Short -> a j b
        is Char -> a j b
        is Int -> a j b
        is Float -> a j b
        else -> null
    }
    is Char -> when (b) {
        is Boolean -> a j b
        is Byte -> a j b
        is Short -> a j b
        is Char -> a j b
        is Int -> a j b
        is Float -> a j b
        else -> null
    }
    is Int -> when (b) {
        is Boolean -> a j b
        is Byte -> a j b
        is Short -> a j b
        is Char -> a j b
        is Int -> a j b
        is Float -> a j b
        else -> null
    }
    is Float -> when (b) {
        is Boolean -> a j b
        is Byte -> a j b
        is Short -> a j b
        is Char -> a j b
        is Int -> a j b
        is Float -> a j b
        else -> null
    }
    else -> null
}

private fun packPrimitivePair(a: Long, b: Long, rightBits: Int): Long =
    (a shl rightBits) or (b and ((1L shl rightBits) - 1L))

// Each ordered type pair has a fixed layout: no per-instance type tags.

@JvmInline
value class BooleanByteJoin(private val capture: Short) : Join<Boolean, Byte> {
    constructor(a: Boolean, b: Byte) : this(packPrimitivePair((if (a) 1L else 0L), b.toLong(), 8).toShort())
    val first: Boolean get() = ((capture.toLong() ushr 8) and 1L) != 0L
    val second: Byte get() = (capture.toLong()).toByte()
    override val a: Boolean get() = first
    override val b: Byte get() = second
}

infix fun Boolean.j(b: Byte): BooleanByteJoin = BooleanByteJoin(this, b)

@JvmInline
value class BooleanShortJoin(private val capture: Int) : Join<Boolean, Short> {
    constructor(a: Boolean, b: Short) : this(packPrimitivePair((if (a) 1L else 0L), b.toLong(), 16).toInt())
    val first: Boolean get() = ((capture.toLong() ushr 16) and 1L) != 0L
    val second: Short get() = (capture.toLong()).toShort()
    override val a: Boolean get() = first
    override val b: Short get() = second
}

infix fun Boolean.j(b: Short): BooleanShortJoin = BooleanShortJoin(this, b)

@JvmInline
value class BooleanCharJoin(private val capture: Int) : Join<Boolean, Char> {
    constructor(a: Boolean, b: Char) : this(packPrimitivePair((if (a) 1L else 0L), b.code.toLong(), 16).toInt())
    val first: Boolean get() = ((capture.toLong() ushr 16) and 1L) != 0L
    val second: Char get() = ((capture.toLong()).toInt() and 0xFFFF).toChar()
    override val a: Boolean get() = first
    override val b: Char get() = second
}

infix fun Boolean.j(b: Char): BooleanCharJoin = BooleanCharJoin(this, b)

@JvmInline
value class BooleanIntJoin(private val capture: Long) : Join<Boolean, Int> {
    constructor(a: Boolean, b: Int) : this(packPrimitivePair((if (a) 1L else 0L), b.toLong(), 32))
    val first: Boolean get() = ((capture ushr 32) and 1L) != 0L
    val second: Int get() = (capture).toInt()
    override val a: Boolean get() = first
    override val b: Int get() = second
}

infix fun Boolean.j(b: Int): BooleanIntJoin = BooleanIntJoin(this, b)

@JvmInline
value class BooleanFloatJoin(private val capture: Long) : Join<Boolean, Float> {
    constructor(a: Boolean, b: Float) : this(packPrimitivePair((if (a) 1L else 0L), b.toRawBits().toLong(), 32))
    val first: Boolean get() = ((capture ushr 32) and 1L) != 0L
    val second: Float get() = Float.fromBits((capture).toInt())
    override val a: Boolean get() = first
    override val b: Float get() = second
}

infix fun Boolean.j(b: Float): BooleanFloatJoin = BooleanFloatJoin(this, b)

@JvmInline
value class ByteBooleanJoin(private val capture: Short) : Join<Byte, Boolean> {
    constructor(a: Byte, b: Boolean) : this(packPrimitivePair(a.toLong(), (if (b) 1L else 0L), 1).toShort())
    val first: Byte get() = (capture.toLong() ushr 1).toByte()
    val second: Boolean get() = ((capture.toLong()) and 1L) != 0L
    override val a: Byte get() = first
    override val b: Boolean get() = second
}

infix fun Byte.j(b: Boolean): ByteBooleanJoin = ByteBooleanJoin(this, b)

@JvmInline
value class ByteShortJoin(private val capture: Int) : Join<Byte, Short> {
    constructor(a: Byte, b: Short) : this(packPrimitivePair(a.toLong(), b.toLong(), 16).toInt())
    val first: Byte get() = (capture.toLong() ushr 16).toByte()
    val second: Short get() = (capture.toLong()).toShort()
    override val a: Byte get() = first
    override val b: Short get() = second
}

infix fun Byte.j(b: Short): ByteShortJoin = ByteShortJoin(this, b)

@JvmInline
value class ByteCharJoin(private val capture: Int) : Join<Byte, Char> {
    constructor(a: Byte, b: Char) : this(packPrimitivePair(a.toLong(), b.code.toLong(), 16).toInt())
    val first: Byte get() = (capture.toLong() ushr 16).toByte()
    val second: Char get() = ((capture.toLong()).toInt() and 0xFFFF).toChar()
    override val a: Byte get() = first
    override val b: Char get() = second
}

infix fun Byte.j(b: Char): ByteCharJoin = ByteCharJoin(this, b)

@JvmInline
value class ByteIntJoin(private val capture: Long) : Join<Byte, Int> {
    constructor(a: Byte, b: Int) : this(packPrimitivePair(a.toLong(), b.toLong(), 32))
    val first: Byte get() = (capture ushr 32).toByte()
    val second: Int get() = (capture).toInt()
    override val a: Byte get() = first
    override val b: Int get() = second
}

infix fun Byte.j(b: Int): ByteIntJoin = ByteIntJoin(this, b)

@JvmInline
value class ByteFloatJoin(private val capture: Long) : Join<Byte, Float> {
    constructor(a: Byte, b: Float) : this(packPrimitivePair(a.toLong(), b.toRawBits().toLong(), 32))
    val first: Byte get() = (capture ushr 32).toByte()
    val second: Float get() = Float.fromBits((capture).toInt())
    override val a: Byte get() = first
    override val b: Float get() = second
}

infix fun Byte.j(b: Float): ByteFloatJoin = ByteFloatJoin(this, b)

@JvmInline
value class ShortBooleanJoin(private val capture: Int) : Join<Short, Boolean> {
    constructor(a: Short, b: Boolean) : this(packPrimitivePair(a.toLong(), (if (b) 1L else 0L), 1).toInt())
    val first: Short get() = (capture.toLong() ushr 1).toShort()
    val second: Boolean get() = ((capture.toLong()) and 1L) != 0L
    override val a: Short get() = first
    override val b: Boolean get() = second
}

infix fun Short.j(b: Boolean): ShortBooleanJoin = ShortBooleanJoin(this, b)

@JvmInline
value class ShortByteJoin(private val capture: Int) : Join<Short, Byte> {
    constructor(a: Short, b: Byte) : this(packPrimitivePair(a.toLong(), b.toLong(), 8).toInt())
    val first: Short get() = (capture.toLong() ushr 8).toShort()
    val second: Byte get() = (capture.toLong()).toByte()
    override val a: Short get() = first
    override val b: Byte get() = second
}

infix fun Short.j(b: Byte): ShortByteJoin = ShortByteJoin(this, b)

@JvmInline
value class ShortCharJoin(private val capture: Int) : Join<Short, Char> {
    constructor(a: Short, b: Char) : this(packPrimitivePair(a.toLong(), b.code.toLong(), 16).toInt())
    val first: Short get() = (capture.toLong() ushr 16).toShort()
    val second: Char get() = ((capture.toLong()).toInt() and 0xFFFF).toChar()
    override val a: Short get() = first
    override val b: Char get() = second
}

infix fun Short.j(b: Char): ShortCharJoin = ShortCharJoin(this, b)

@JvmInline
value class ShortIntJoin(private val capture: Long) : Join<Short, Int> {
    constructor(a: Short, b: Int) : this(packPrimitivePair(a.toLong(), b.toLong(), 32))
    val first: Short get() = (capture ushr 32).toShort()
    val second: Int get() = (capture).toInt()
    override val a: Short get() = first
    override val b: Int get() = second
}

infix fun Short.j(b: Int): ShortIntJoin = ShortIntJoin(this, b)

@JvmInline
value class ShortFloatJoin(private val capture: Long) : Join<Short, Float> {
    constructor(a: Short, b: Float) : this(packPrimitivePair(a.toLong(), b.toRawBits().toLong(), 32))
    val first: Short get() = (capture ushr 32).toShort()
    val second: Float get() = Float.fromBits((capture).toInt())
    override val a: Short get() = first
    override val b: Float get() = second
}

infix fun Short.j(b: Float): ShortFloatJoin = ShortFloatJoin(this, b)

@JvmInline
value class CharBooleanJoin(private val capture: Int) : Join<Char, Boolean> {
    constructor(a: Char, b: Boolean) : this(packPrimitivePair(a.code.toLong(), (if (b) 1L else 0L), 1).toInt())
    val first: Char get() = ((capture.toLong() ushr 1).toInt() and 0xFFFF).toChar()
    val second: Boolean get() = ((capture.toLong()) and 1L) != 0L
    override val a: Char get() = first
    override val b: Boolean get() = second
}

infix fun Char.j(b: Boolean): CharBooleanJoin = CharBooleanJoin(this, b)

@JvmInline
value class CharByteJoin(private val capture: Int) : Join<Char, Byte> {
    constructor(a: Char, b: Byte) : this(packPrimitivePair(a.code.toLong(), b.toLong(), 8).toInt())
    val first: Char get() = ((capture.toLong() ushr 8).toInt() and 0xFFFF).toChar()
    val second: Byte get() = (capture.toLong()).toByte()
    override val a: Char get() = first
    override val b: Byte get() = second
}

infix fun Char.j(b: Byte): CharByteJoin = CharByteJoin(this, b)

@JvmInline
value class CharShortJoin(private val capture: Int) : Join<Char, Short> {
    constructor(a: Char, b: Short) : this(packPrimitivePair(a.code.toLong(), b.toLong(), 16).toInt())
    val first: Char get() = ((capture.toLong() ushr 16).toInt() and 0xFFFF).toChar()
    val second: Short get() = (capture.toLong()).toShort()
    override val a: Char get() = first
    override val b: Short get() = second
}

infix fun Char.j(b: Short): CharShortJoin = CharShortJoin(this, b)

@JvmInline
value class CharIntJoin(private val capture: Long) : Join<Char, Int> {
    constructor(a: Char, b: Int) : this(packPrimitivePair(a.code.toLong(), b.toLong(), 32))
    val first: Char get() = ((capture ushr 32).toInt() and 0xFFFF).toChar()
    val second: Int get() = (capture).toInt()
    override val a: Char get() = first
    override val b: Int get() = second
}

infix fun Char.j(b: Int): CharIntJoin = CharIntJoin(this, b)

@JvmInline
value class CharFloatJoin(private val capture: Long) : Join<Char, Float> {
    constructor(a: Char, b: Float) : this(packPrimitivePair(a.code.toLong(), b.toRawBits().toLong(), 32))
    val first: Char get() = ((capture ushr 32).toInt() and 0xFFFF).toChar()
    val second: Float get() = Float.fromBits((capture).toInt())
    override val a: Char get() = first
    override val b: Float get() = second
}

infix fun Char.j(b: Float): CharFloatJoin = CharFloatJoin(this, b)

@JvmInline
value class IntBooleanJoin(private val capture: Long) : Join<Int, Boolean> {
    constructor(a: Int, b: Boolean) : this(packPrimitivePair(a.toLong(), (if (b) 1L else 0L), 1))
    val first: Int get() = (capture ushr 1).toInt()
    val second: Boolean get() = ((capture) and 1L) != 0L
    override val a: Int get() = first
    override val b: Boolean get() = second
}

infix fun Int.j(b: Boolean): IntBooleanJoin = IntBooleanJoin(this, b)

@JvmInline
value class IntByteJoin(private val capture: Long) : Join<Int, Byte> {
    constructor(a: Int, b: Byte) : this(packPrimitivePair(a.toLong(), b.toLong(), 8))
    val first: Int get() = (capture ushr 8).toInt()
    val second: Byte get() = (capture).toByte()
    override val a: Int get() = first
    override val b: Byte get() = second
}

infix fun Int.j(b: Byte): IntByteJoin = IntByteJoin(this, b)

@JvmInline
value class IntShortJoin(private val capture: Long) : Join<Int, Short> {
    constructor(a: Int, b: Short) : this(packPrimitivePair(a.toLong(), b.toLong(), 16))
    val first: Int get() = (capture ushr 16).toInt()
    val second: Short get() = (capture).toShort()
    override val a: Int get() = first
    override val b: Short get() = second
}

infix fun Int.j(b: Short): IntShortJoin = IntShortJoin(this, b)

@JvmInline
value class IntCharJoin(private val capture: Long) : Join<Int, Char> {
    constructor(a: Int, b: Char) : this(packPrimitivePair(a.toLong(), b.code.toLong(), 16))
    val first: Int get() = (capture ushr 16).toInt()
    val second: Char get() = ((capture).toInt() and 0xFFFF).toChar()
    override val a: Int get() = first
    override val b: Char get() = second
}

infix fun Int.j(b: Char): IntCharJoin = IntCharJoin(this, b)

@JvmInline
value class IntFloatJoin(private val capture: Long) : Join<Int, Float> {
    constructor(a: Int, b: Float) : this(packPrimitivePair(a.toLong(), b.toRawBits().toLong(), 32))
    val first: Int get() = (capture ushr 32).toInt()
    val second: Float get() = Float.fromBits((capture).toInt())
    override val a: Int get() = first
    override val b: Float get() = second
}

infix fun Int.j(b: Float): IntFloatJoin = IntFloatJoin(this, b)

@JvmInline
value class FloatBooleanJoin(private val capture: Long) : Join<Float, Boolean> {
    constructor(a: Float, b: Boolean) : this(packPrimitivePair(a.toRawBits().toLong(), (if (b) 1L else 0L), 1))
    val first: Float get() = Float.fromBits((capture ushr 1).toInt())
    val second: Boolean get() = ((capture) and 1L) != 0L
    override val a: Float get() = first
    override val b: Boolean get() = second
}

infix fun Float.j(b: Boolean): FloatBooleanJoin = FloatBooleanJoin(this, b)

@JvmInline
value class FloatByteJoin(private val capture: Long) : Join<Float, Byte> {
    constructor(a: Float, b: Byte) : this(packPrimitivePair(a.toRawBits().toLong(), b.toLong(), 8))
    val first: Float get() = Float.fromBits((capture ushr 8).toInt())
    val second: Byte get() = (capture).toByte()
    override val a: Float get() = first
    override val b: Byte get() = second
}

infix fun Float.j(b: Byte): FloatByteJoin = FloatByteJoin(this, b)

@JvmInline
value class FloatShortJoin(private val capture: Long) : Join<Float, Short> {
    constructor(a: Float, b: Short) : this(packPrimitivePair(a.toRawBits().toLong(), b.toLong(), 16))
    val first: Float get() = Float.fromBits((capture ushr 16).toInt())
    val second: Short get() = (capture).toShort()
    override val a: Float get() = first
    override val b: Short get() = second
}

infix fun Float.j(b: Short): FloatShortJoin = FloatShortJoin(this, b)

@JvmInline
value class FloatCharJoin(private val capture: Long) : Join<Float, Char> {
    constructor(a: Float, b: Char) : this(packPrimitivePair(a.toRawBits().toLong(), b.code.toLong(), 16))
    val first: Float get() = Float.fromBits((capture ushr 16).toInt())
    val second: Char get() = ((capture).toInt() and 0xFFFF).toChar()
    override val a: Float get() = first
    override val b: Char get() = second
}

infix fun Float.j(b: Char): FloatCharJoin = FloatCharJoin(this, b)

@JvmInline
value class FloatIntJoin(private val capture: Long) : Join<Float, Int> {
    constructor(a: Float, b: Int) : this(packPrimitivePair(a.toRawBits().toLong(), b.toLong(), 32))
    val first: Float get() = Float.fromBits((capture ushr 32).toInt())
    val second: Int get() = (capture).toInt()
    override val a: Float get() = first
    override val b: Int get() = second
}

infix fun Float.j(b: Int): FloatIntJoin = FloatIntJoin(this, b)
