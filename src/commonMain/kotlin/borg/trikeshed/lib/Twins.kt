@file:Suppress("UNCHECKED_CAST", "NonAsciiCharacters")

package borg.trikeshed.lib

import kotlin.jvm.JvmInline
import kotlin.jvm.JvmStatic

// ============================================================================
// Pack helpers — reusable bit-shuffle primitives
// ============================================================================

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
// Concrete overloads win at compile time when types are known (e.g. Int.j(Int)).
// The generic fallback does runtime type dispatch so that callers in generic
// contexts (zipWithNext<Int>, Twin<Int> from type parameter T) still produce
// the dense value class — preventing megamorphic dispatch on Twin<T>.a/.b
// within a single Series<Twin<T>>.
// ============================================================================

fun autoTwin(a: Byte,   b: Byte):   Twin<Byte>   = Twyte(packBytes(a, b))
fun autoTwin(a: Short,  b: Short):  Twin<Short>  = Twhort(packShorts(a, b))
fun autoTwin(a: Char,   b: Char):   Twin<Char>   = Twhar(packChars(a, b))
fun autoTwin(a: Int,    b: Int):    Twin<Int>    = TwInt(packInts(a, b))
fun autoTwin(a: Float,  b: Float):  Twin<Float>  = TwInt(packFloats(a, b)) as Twin<Float>

/**
 * Generic fallback with runtime type dispatch.
 *
 * When called from a generic context (type parameter T not known at compile
 * time), runtime [is] checks select the same dense value class that the
 * concrete overloads would produce.  This ensures every [Twin<Int>] in a
 * [Series] is a [TwInt], avoiding megamorphic dispatch on [Twin.a]/[Twin.b].
 */
fun <T> autoTwin(a: T, b: T): Twin<T> {
    return when {
        a is Int    && b is Int    -> TwInt(packInts(a, b))     as Twin<T>
        a is Short  && b is Short  -> Twhort(packShorts(a, b))  as Twin<T>
        a is Byte   && b is Byte   -> Twyte(packBytes(a, b))    as Twin<T>
        a is Char   && b is Char   -> Twhar(packChars(a, b))    as Twin<T>
        a is Float  && b is Float  -> TwInt(packFloats(a, b))   as Twin<T>
        else                       -> a j b
    }
}

// ============================================================================
// Infix j overloads — delegate to autoTwin for identical-type pairs
// ============================================================================

infix fun Int.j(b: Int):     Twin<Int>    = autoTwin(this, b)
infix fun Short.j(b: Short): Twin<Short>  = autoTwin(this, b)
infix fun Byte.j(b: Byte):   Twin<Byte>   = autoTwin(this, b)
infix fun Char.j(b: Char):   Twin<Char>   = autoTwin(this, b)
infix fun Float.j(b: Float): Twin<Float>  = autoTwin(this, b)
