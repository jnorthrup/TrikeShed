package borg.trikeshed.lib

import kotlin.jvm.JvmInline

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