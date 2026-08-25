@file:Suppress("INLINE_CLASS_DEPRECATED")

package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ── Dense-Packed Twins ──────────────────────────────────────────
//
// Value classes that pack 2×Int into 1 Long.
// 0 allocation, 1 shift + 1 mask per access.
// Gated to hot paths only: zipWithNext, tensor construction, cursor α-chains.

/** TwInt — two Ints packed in a single Long. Zero allocation on JVM. */
 inline  class TwInt(val raw: Long) {
    constructor(a: Int, b: Int) : this((a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL))

    val a: Int get() = (raw ushr 32).toInt()
    val b: Int get() = raw.toInt()

    operator fun component1(): Int = a
    operator fun component2(): Int = b

    fun asJoin(): Twin<Int> = a j b
}

/**
 * BudgetCoord — NARS priority/durability/quality triple, 3×20-bit fields packed
 * in 60 bits of a Long. THE canonical budget type (the twin in manifold/ is
 * deprecated). Int accessors expose raw fixed-point bits; the `pf/df/qf` float
 * view reads them as [0,1] NARS budget values (scaled by 2^20−1).
 */
inline  class BudgetCoord(val packed: Long) {
    /** Priority — bits 40..59 */
    val p: Int get() = ((packed ushr 40) and 0xFFFFF).toInt()
    /** Durability — bits 20..39 */
    val d: Int get() = ((packed ushr 20) and 0xFFFFF).toInt()
    /** Quality — bits 0..19 */
    val q: Int get() = (packed and 0xFFFFF).toInt()

    /** Priority as a [0,1] NARS budget value. */
    val pf: Float get() = p.toFloat() / SCALE
    /** Durability as a [0,1] NARS budget value. */
    val df: Float get() = d.toFloat() / SCALE
    /** Quality as a [0,1] NARS budget value. */
    val qf: Float get() = q.toFloat() / SCALE

    constructor(p: Int, d: Int, q: Int) : this(
        ((p.toLong() and 0xFFFFF) shl 40) or
        ((d.toLong() and 0xFFFFF) shl 20) or
        (q.toLong() and 0xFFFFF)
    )

    operator fun component1(): Int = p
    operator fun component2(): Int = d
    operator fun component3(): Int = q

    companion object {
        const val SCALE: Int = 0xFFFFF  // 2^20 - 1

        /** Factory: pack three [0,1] floats. */
        operator fun invoke(p: Float, d: Float, q: Float): BudgetCoord = BudgetCoord(
            (p.coerceIn(0f, 1f) * SCALE).toInt(),
            (d.coerceIn(0f, 1f) * SCALE).toInt(),
            (q.coerceIn(0f, 1f) * SCALE).toInt(),
        )

        /** Full-budget convenience (p = d = q = 1). */
        fun full(): BudgetCoord = BudgetCoord(SCALE, SCALE, SCALE)

        /** Zero-attention convenience (evicted / archived). */
        fun zero(): BudgetCoord = BudgetCoord(0L)
    }
}

// ── AutoTwinContext ─────────────────────────────────────────────
//
// Fixes the megamorphic probe in autoTwin: first call probes once,
// stores the packer as a captured lambda, all subsequent calls go
// through a single monomorphic call site.

class AutoTwinContext<T>(private var locked: ((T, T) -> Twin<T>)? = null) {
    fun twin(a: T, b: T): Twin<T> {
        val packer = locked
        if (packer != null) return packer(a, b)
        val resolved: (T, T) -> Twin<T> = { x, y -> x j y }
        locked = resolved
        return resolved(a, b)
    }
}
