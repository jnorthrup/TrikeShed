package borg.trikeshed.miniduck

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*

/** Expose the row as a Series<Any?> for uniform Cursor traversal. */
fun RowVec.asSeries(): Series<Any?> = size j { this[it] }

/** Bridge into TrikeShed RowVec for columnar operations (select, valueAt, etc.).
 *  Values are the scalar cells; metas are plain ColumnMeta stubs keyed by index.
 *  Shell rows (size=0) produce an empty RowVec. */
fun RowVec.toRowVec(): RowVec {
    val sz = size
    if (sz == 0) return ReifiedSplitSeries2(
        leftSeries  = 0 j { _ -> throw IndexOutOfBoundsException("empty row") },
        rightSeries = 0 j { _ -> throw IndexOutOfBoundsException("empty row") }
    )
    return ReifiedSplitSeries2(
        leftSeries  = sz j { this[it] },
        rightSeries = sz j { i -> { ColumnMeta("col$i", IOMemento.IoString) } }
    )
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

/**
 * Abstract base for rows that carry a lazy child family.
 *
 * The child is computed once and cached. Subclasses must call `loadChild`
 * in their `child` getter:
 *
 * ```
 * var cached: Series<MiniRowVec>? = null
 * override val child: Series<MiniRowVec>? get() = loadChild(cached) { factory() }
 * ```
 */
abstract class LazyChildRowVec : RowVec  {
    /**
     * Load a child lazily with caching.
     *
     * Returns `cached` if already populated. Otherwise calls `factory`,
     * stores the result in `cached`, and returns it. If `factory` returns
     * null, `cached` is set to null and subsequent calls return null
     * immediately (no further factory invocations).
     *
     * Subclasses pass their own `cached` var as the first argument.
     * The lambda is responsible for assigning to `cached` on success:
     * `loadChild(cached) { factory()?.also { cached = it } }`
     */
    protected fun loadChild(
        cached: Series<RowVec>?,
        factory: () -> Series<RowVec>?,
    ): Series<RowVec>? {
        var state = cached
        return state ?: factory()?.also { state = it }
    }
}
