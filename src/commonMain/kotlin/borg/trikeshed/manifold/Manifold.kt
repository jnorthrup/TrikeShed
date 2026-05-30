package borg.trikeshed.manifold

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

// ── ManifoldK — NARS3 concept facet keys ────────────────────────
//
// Elevated from ad-hoc ManifoldConcept<P> : RowVec to
// first-class CRMS participant via OpK.

sealed class ManifoldK<out R> : OpK<R>() {
    data object Angular : ManifoldK<Long>()
    data object Budget  : ManifoldK<BudgetCoord>()
    data object Payload : ManifoldK<Any?>()
}

/** ManifoldRow = FacetedRow<ManifoldK<*>> — a concept is a point in ManifoldK-space. */
typealias ManifoldRow = FacetedRow<ManifoldK<*>>

/** Construct a manifold concept as a FacetedRow. */
fun <P> manifold(angular: Long, budget: BudgetCoord, payload: P): ManifoldRow =
    ManifoldK.Angular j { op: ManifoldK<*> ->
        when (op) {
            ManifoldK.Angular -> angular
            ManifoldK.Budget  -> budget
            ManifoldK.Payload -> payload
        }
    }

// ── ManifoldRow → RowVec ────────────────────────────────────────

/** ManifoldRow participates in Cursor algebra via RowVec projection. */
fun ManifoldRow.asRowVec(): RowVec = 2 j { i ->
    @Suppress("UNCHECKED_CAST")
    when (i) {
        0    -> (b(ManifoldK.Angular) as Long) as Any? j { ColumnMeta("angular", IOMemento.IoLong) }
        else -> (b(ManifoldK.Budget) as BudgetCoord).packed as Any? j { ColumnMeta("budget", IOMemento.IoLong) }
    }
}

/** Hamming distance between two manifold angular coordinates. */
fun hammingDistance(a: ManifoldRow, b: ManifoldRow): Int {
    @Suppress("UNCHECKED_CAST")
    val xa = a.b(ManifoldK.Angular) as Long
    @Suppress("UNCHECKED_CAST")
    val xb = b.b(ManifoldK.Angular) as Long
    return (xa xor xb).countOneBits()
}
