package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec as CursorRowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.lib.j

/** Expose the row as a Series<Any?> for uniform Cursor traversal. */
fun MiniRowVec.asSeries(): Series<Any?> = size j { this[it] }

/** Bridge into TrikeShed RowVec for columnar operations (select, valueAt, etc.).
 *  Values are the scalar cells; metas are plain ColumnMeta stubs keyed by index.
 *  Shell rows (size=0) produce an empty RowVec. */
fun MiniRowVec.toRowVec(): CursorRowVec {
    val sz = size
    if (sz == 0) return ReifiedSplitSeries2(
        leftSeries = 0 j { _ -> throw IndexOutOfBoundsException("empty row") },
        rightSeries = 0 j { _ -> throw IndexOutOfBoundsException("empty row") }
    )
    return ReifiedSplitSeries2(
        leftSeries = sz j { this[it] },
        rightSeries = sz j { i -> { "col$i" j IOMemento.IoString } }
    )
}

/** Wrap any TrikeShed RowVec as a MiniRowVec — the reverse bridge. */
class WrappedRowVec(val inner: CursorRowVec, override val child: Series<MiniRowVec>? = null) : MiniRowVec() {
    override val size: Int get() = inner.a
    override fun get(index: Int): Any? = (inner as ReifiedSplitSeries2<*, *>).leftSeries[index]
}

/* ═════════════════════════════════════════════════════════════════════════════
 * Lazy child infrastructure.
 *
 * Seven RowVec families share the same lazy-loading pattern for `child`:
 *   DocRowVec   — constructor param, no caching (children come from parent)
 *   ViewRowVec  — double-checked loading + caching viavar
 *   BlobRowVec  — factory function called on every child access (no caching)
 *   JsonRowVec  — same: factory called every access (no caching)
 *   YamlRowVec  — same: factory called every access (no caching)
 *   ManifoldConcept — fixed single-element child (no loader, no caching)
 *   BlockRowVec — fixed child built from internal mutable list (no factory)
 *
 * `loadChild` unifies the caching variant (ViewRowVec).
 * Subclasses that want caching implement:
 *  var cached: Series<MiniRowVec>? = null
 *   override val child: Series<MiniRowVec>? get() = loadChild(cached) { factory()?.also { cached = it } }
 *
 * `LazyChildRowVec` is the abstract base for subclasses that carry a deferred
 * child family. Leaf rows that never load children (e.g. scalar-only rows)
 * extend MiniRowVec directly and override `child` with `null`.
 * ════════════════════════════════════════════════════════════════════════════ */

abstract class LazyChildRowVec : MiniRowVec() {
    protected fun loadChild(
        cached: Series<MiniRowVec>?,
        factory: () -> Series<MiniRowVec>?,
    ): Series<MiniRowVec>? {
        var state = cached
        return state ?: factory()?.also { state = it }
    }
}
