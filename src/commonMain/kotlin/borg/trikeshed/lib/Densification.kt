@file:Suppress("NonAsciiCharacters", "FunctionName")

package borg.trikeshed.lib

import kotlin.jvm.JvmInline
import kotlin.math.ln

/**
 * Densified Join64 — register-dense Twin<Int> packed into a single Long.
 *
 * Packs two 32-bit unsigned values into one Long for cache-friendly,
 * register-dense access. Used in the densification seam between the
 * compiler's sparse pointer-chasing Join and the cursor's indexed RowVec.
 *
 * @see TwInt — the canonical dense-packed Int twin (same layout, wider scope)
 */
inline  class DensifiedJoin(private val payload: Long) : Twin<Int> {
    override val a: Int get() = (payload ushr 32).toInt()
    override val b: Int get() = payload.toInt()

    companion object {
        operator fun invoke(a: Int, b: Int): DensifiedJoin =
            DensifiedJoin(packInts(a, b))
    }
}

/**
 * JSON value interval extracted from JsonBitmap confix boundaries.
 *
 * Represents the character range of a single value in a densified JSON
 * structure, bounded by structural events (open/close confix tokens).
 */
data class ValueInterval(
    val chars: CharSequence,
)

/**
 * Architectural projection — describes how a layer specializes Join.
 *
 * Each layer in the stack (lib, cursor, WAM, CCEK, channelization, reactor)
 * is a different projection of the same Join<A,B> substrate. The
 * densificationFactor measures how much denser the representation becomes
 * at that layer relative to the raw lib baseline.
 */
data class Projection<T>(
    val layer: String,
    val signature: String,
    val densificationFactor: Double,
) {
    companion object {
        fun lib(): Projection<Join<*, *>> = Projection("lib", "Join<A,B>", 1.0)
        fun cursor(): Projection<Join<*, *>> = Projection("cursor", "Join<Any?,()`ColumnMeta↻`>", 4.0)
        fun wam(): Projection<Join<*, *>> = Projection("wam", "Join<Map<String,String>,Boolean>", 16.0)
        // CCEK uses CoroutineContext.Key and Element — referenced as string only
        fun ccek(): Projection<Join<*, *>> = Projection("ccek", "Join<Key<*>,Element>", 8.0)
    }
}

/**
 * RowVec reference — the canonical typealias lives in borg.trikeshed.cursor.
 * This is here only for the Projection type signature.
 */
// No local RowVec typealias — it exists in borg.trikeshed.cursor

/**
 * ColumnMeta reference — the canonical typealias lives in borg.trikeshed.cursor.
 * This is here only for the Projection type signature.
 */
// No local ColumnMeta typealias — it exists in borg.trikeshed.cursor

/**
 * TypeMemento reference — the canonical interface lives in borg.trikeshed.cursor.
 */
// No local TypeMemento typealias — it exists in borg.trikeshed.cursor

/**
 * Cursor reference — the canonical typealias lives in borg.trikeshed.cursor.
 */
// No local Cursor typealias — it exists in borg.trikeshed.cursor

/**
 * Densification factor helper — computes the compression ratio from
 * sparse (heap pointers, repeated keys, branching) to dense (indexed
 * access, register-packed, linear).
 */
fun densificationFactor(sparseSize: Int, denseSize: Int): Double =
    if (denseSize == 0) 0.0 else ln(sparseSize.toDouble().coerceAtLeast(1.0) / denseSize.toDouble().coerceAtLeast(1.0)) + 1.0
