package borg.trikeshed.lib

import kotlin.jvm.JvmInline

// ── Pack functions ────────────────────────────────────────────────────────

inline fun packBytes(a: Byte, b: Byte): Short =
    ((a.toInt() and 0xFF shl 8) or (b.toInt() and 0xFF)).toShort()

inline fun packShorts(a: Short, b: Short): Int =
    ((a.toInt() and 0xFFFF) shl 16) or (b.toInt() and 0xFFFF)

inline fun packChars(a: Char, b: Char): Int =
    (a.code shl 16) or b.code

inline fun packInts(a: Int, b: Int): Long =
    (a.toLong() shl 32) or (b.toLong() and 0xFFFF_FFFFL)

inline fun packFloats(a: Float, b: Float): Long =
    (a.toBits().toLong() shl 32) or (b.toBits().toLong() and 0xFFFF_FFFFL)

// ── autoTwin overloads ────────────────────────────────────────────────────

fun autoTwin(a: Byte,   b: Byte):   Twin<Byte>   = Twyte(packBytes(a, b))
fun autoTwin(a: Short,  b: Short):  Twin<Short>  = Twhort(packShorts(a, b))
fun autoTwin(a: Char,   b: Char):   Twin<Char>   = Twhar(packChars(a, b))
fun autoTwin(a: Int,    b: Int):    Twin<Int>    = TwInt(packInts(a, b))
@Suppress("UNCHECKED_CAST")
fun autoTwin(a: Float,  b: Float):  Twin<Float>  = TwInt(packFloats(a, b)) as Twin<Float>
fun <T> autoTwin(a: T, b: T): Twin<T> = a j b

// ── Infix packed constructors ─────────────────────────────────────────────

inline infix fun Int.j(b: Int): Twin<Int> = TwInt(((this.toLong() shl 32) or (b.toLong())))
inline infix fun Short.j(b: Short): Twin<Short> = Twhort(((this.toInt() shl 16) or (b.toInt())))
inline infix fun Byte.j(b: Byte): Twin<Byte> = Twyte(((this.toInt() shl 8) or (b.toInt())).toShort())
inline infix fun Char.j(b: Char): Twin<Char> = Twhar(((this.code shl 16) or (b.code)))



@JvmInline
value class TwInt(private val capture: Long) : Twin<Int> {
    override val a: Int get() = (capture shr 32).toInt()
    override val b: Int get() = (capture and 0xFFFF_FFFFL).toInt()
}

@JvmInline
value class Twhort(private val capture: Int) : Twin<Short> {
    override val a: Short get() = (capture shr 16).toShort()
    override val b: Short get() = (capture and 0xFFFF).toShort()
}

@JvmInline
value class Twhar(private val capture: Int) : Twin<Char> {
    override val a: Char get() = (capture shr 16).toChar()
    override val b: Char get() = (capture and 0xFFFF).toChar()
}

@JvmInline
value class Twyte(private val capture: Short) : Twin<Byte> {
    override val a: Byte get() = (1 * capture shr (8)).toByte()
    override val b: Byte get() = (1 * capture and 0xFF).toByte()
}

