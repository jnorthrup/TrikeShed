package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ── ColK — columnar facet keys ──────────────────────────────────
//
// Sibling of TextK under OpK. Enables FacetedRow<ColK<*>> as the
// RowVec isomorphism, making Cursor rows and CharStr instances
// composable under the same FacetedRow algebra.

sealed class ColK<out R> : OpK<R>() {
    data class ByIndex(val col: Int)          : ColK<Any?>()
    data class ByName(val name: CharSequence) : ColK<Any?>()
    data object Meta                          : ColK<Series<ColumnMeta>>()
    data object Width                         : ColK<Int>()
}

// ── RowVec ↔ FacetedRow<ColK<*>> isomorphism ────────────────────

/** Lift: RowVec → FacetedRow<ColK<*>> */
fun RowVec.asFaceted(): FacetedRow<ColK<*>> {
    val self = this
    return ColK.Width j { op: ColK<*> ->
        when (op) {
            is ColK.ByIndex -> self.b(op.col).a
            is ColK.ByName  -> {
                val idx = (0 until self.a).first { c -> self.b(c).b().name == op.name }
                self.b(idx).a
            }
            ColK.Meta       -> {
                val s = self.a
                val fn = { c: Int -> self.b(c).b() }  // avoids recursive typealias expansion
                (s j fn) as Any?
            }
            ColK.Width      -> self.a as Any?
        }
    }
}

/** Lower: FacetedRow<ColK<*>> → RowVec */
@Suppress("UNCHECKED_CAST")
fun FacetedRow<ColK<*>>.asRowVec(): RowVec {
    val w = b(ColK.Width) as Int
    val metaSeries = b(ColK.Meta) as Series<ColumnMeta>
    val self = this
    return w j { i: Int ->
        self.b(ColK.ByIndex(i)) j { metaSeries[i] }
    }
}
