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

/** BudgetCoord — 3×20-bit fields packed in 60 bits of a Long. */
inline  class BudgetCoord(val packed: Long) {
    /** Priority — bits 40..59 */
    val p: Int get() = ((packed ushr 40) and 0xFFFFF).toInt()
    /** Durability — bits 20..39 */
    val d: Int get() = ((packed ushr 20) and 0xFFFFF).toInt()
    /** Quality — bits 0..19 */
    val q: Int get() = (packed and 0xFFFFF).toInt()

    constructor(p: Int, d: Int, q: Int) : this(
        ((p.toLong() and 0xFFFFF) shl 40) or
        ((d.toLong() and 0xFFFFF) shl 20) or
        (q.toLong() and 0xFFFFF)
    )

    operator fun component1(): Int = p
    operator fun component2(): Int = d
    operator fun component3(): Int = q
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
