package borg.trikeshed.lib

import kotlin.jvm.JvmInline

// ── Pack functions ────────────────────────────────────────────────────────

inline fun packBytes(a: Byte, b: Byte): Short =
    (((a.toInt() and 0xFF) shl 8) or (b.toInt() and 0xFF)).toShort()

inline fun packShorts(a: Short, b: Short): Int =
    ((a.toInt() and 0xFFFF) shl 16) or (b.toInt() and 0xFFFF)

inline fun packChars(a: Char, b: Char): Int =
    (a.code shl 16) or b.code

inline fun packInts(a: Int, b: Int): Long =
    (a.toLong() shl 32) or (b.toLong() and 0xFFFF_FFFFL)

inline fun packFloats(a: Float, b: Float): Long =
    (a.toRawBits().toLong() shl 32) or (b.toRawBits().toLong() and 0xFFFF_FFFFL)

// ── autoTwin overloads ────────────────────────────────────────────────────

fun autoTwin(a: Boolean, b: Boolean): TwBoolean = TwBoolean(a, b)
fun autoTwin(a: Byte, b: Byte): Twyte = Twyte(packBytes(a, b))
fun autoTwin(a: Short, b: Short): Twhort = Twhort(packShorts(a, b))
fun autoTwin(a: Char, b: Char): Twhar = Twhar(packChars(a, b))
fun autoTwin(a: Int, b: Int): TwInt = TwInt(packInts(a, b))
fun autoTwin(a: Float, b: Float): TwFloat = TwFloat(packFloats(a, b))
fun <T> autoTwin(a: T, b: T): Twin<T> = a j b

// ── Infix packed constructors ─────────────────────────────────────────────

infix fun Boolean.j(b: Boolean): TwBoolean = autoTwin(this, b)
infix fun Int.j(b: Int): TwInt = autoTwin(this, b)
infix fun Short.j(b: Short): Twhort = autoTwin(this, b)
infix fun Byte.j(b: Byte): Twyte = autoTwin(this, b)
infix fun Char.j(b: Char): Twhar = autoTwin(this, b)
infix fun Float.j(b: Float): TwFloat = autoTwin(this, b)



/** Concrete access uses primitive first/second; the Join bridge may box. */
@JvmInline
value class TwInt(private val capture: Long) : Twin<Int> {
    val first: Int get() = (capture ushr 32).toInt()
    val second: Int get() = capture.toInt()
    override val a: Int get() = first
    override val b: Int get() = second
}

@JvmInline
value class Twhort(private val capture: Int) : Twin<Short> {
    val first: Short get() = (capture ushr 16).toShort()
    val second: Short get() = capture.toShort()
    override val a: Short get() = first
    override val b: Short get() = second
}

@JvmInline
value class Twhar(private val capture: Int) : Twin<Char> {
    val first: Char get() = (capture ushr 16).toChar()
    val second: Char get() = (capture and 0xFFFF).toChar()
    override val a: Char get() = first
    override val b: Char get() = second
}

@JvmInline
value class Twyte(private val capture: Short) : Twin<Byte> {
    val first: Byte get() = (capture.toInt() ushr 8).toByte()
    val second: Byte get() = capture.toByte()
    override val a: Byte get() = first
    override val b: Byte get() = second
}

@JvmInline
value class TwFloat(private val capture: Long) : Twin<Float> {
    val first: Float get() = Float.fromBits((capture ushr 32).toInt())
    val second: Float get() = Float.fromBits(capture.toInt())
    override val a: Float get() = first
    override val b: Float get() = second
}

@JvmInline
value class TwBoolean(private val capture: Byte) : Twin<Boolean> {
    constructor(a: Boolean, b: Boolean) : this(((if (a) 2 else 0) or (if (b) 1 else 0)).toByte())
    val first: Boolean get() = (capture.toInt() and 2) != 0
    val second: Boolean get() = (capture.toInt() and 1) != 0
    override val a: Boolean get() = first
    override val b: Boolean get() = second
}
