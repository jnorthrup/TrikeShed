@file:Suppress("UNCHECKED_CAST", "NonAsciiCharacters")

package borg.trikeshed.lib

import kotlin.jvm.JvmInline
import kotlin.jvm.JvmStatic

// ============================================================================
// Pack helpers — reusable bit-shuffle primitives
// ============================================================================

fun packBytes(a: Byte, b: Byte): Short =
    ((a.toInt() and 0xFF shl 8) or (b.toInt() and 0xFF)).toShort()

fun packShorts(a: Short, b: Short): Int =
    ((a.toInt() and 0xFFFF) shl 16) or (b.toInt() and 0xFFFF)

fun packChars(a: Char, b: Char): Int =
    (a.code shl 16) or b.code

fun packInts(a: Int, b: Int): Long =
    (a.toLong() shl 32) or (b.toLong() and 0xFFFF_FFFFL)

fun packFloats(a: Float, b: Float): Long =
    (a.toBits().toLong() shl 32) or (b.toBits().toLong() and 0xFFFF_FFFFL)

// ============================================================================
// Dense-packed value classes — zero allocation when used as Twin<T>
//
// Tradeoff: each access costs 2 ALU ops (shift + mask), but saves 1-2 heap
// allocations vs the PairJoin fallback.  Best for hot paths where Twin objects
// are created in bulk (cursor scans, tensor construction, α-chains).
// ============================================================================

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
    override val a: Byte get() = (capture.toInt() shr 8).toByte()
    override val b: Byte get() = (capture.toInt() and 0xFF).toByte()
}

// ============================================================================
// autoTwin — single entry point that selects the densest representation
//
// Overloads + generic fallback.  Callers get the tightest pack automatically.
// For types without a dense representation (Long, Double, Any?), falls back
// to PairJoin — a @JvmInline value class wrapping a Pair, so still only 1
// allocation (the Pair itself).
// ============================================================================

fun autoTwin(a: Byte,   b: Byte):   Twin<Byte>   = Twyte(packBytes(a, b))
fun autoTwin(a: Short,  b: Short):  Twin<Short>  = Twhort(packShorts(a, b))
fun autoTwin(a: Char,   b: Char):   Twin<Char>   = Twhar(packChars(a, b))
fun autoTwin(a: Int,    b: Int):    Twin<Int>    = TwInt(packInts(a, b))
fun autoTwin(a: Float,  b: Float):  Twin<Float>  = TwInt(packFloats(a, b)) as Twin<Float>
fun <T> autoTwin(a: T,  b: T):      Twin<T>      = Join.Companion.PairJoin(a to b) as Twin<T>

// ============================================================================
// Infix j overloads — delegate to autoTwin for identical-type pairs
// ============================================================================

infix fun Int.j(b: Int):     Twin<Int>    = autoTwin(this, b)
infix fun Short.j(b: Short): Twin<Short>  = autoTwin(this, b)
infix fun Byte.j(b: Byte):   Twin<Byte>   = autoTwin(this, b)
infix fun Char.j(b: Char):   Twin<Char>   = autoTwin(this, b)
infix fun Float.j(b: Float): Twin<Float>  = autoTwin(this, b)
