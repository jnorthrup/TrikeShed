package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.j

/**
 * MiniRowVec: the foundational row abstraction for MiniDuck.
 *
 * A row is a Series of Any? cells plus an optional lazy child family.
 * Zero-length rows are valid -- meaning may be deferred entirely into children.
 *
 * Hierarchy:
 *   MiniRowVec (sealed root)
 *     BlockRowVec  -- mutable/sealed chunky block (DuckDB-style)
 *     DocRowVec    -- flat document fields + nested child expansion
 *     ViewRowVec   -- Couch id/key/value/doc + deferred doc
 *     JsonRowVec   -- parse-tree over JSON blob
 *     YamlRowVec   -- parse-tree over YAML blob
 *     BlobRowVec   -- zero-length or sparse shell for opaque payloads
 */
sealed class MiniRowVec {
    /** Number of scalar cells in this row (may be 0). */
    abstract val size: Int

    /** Get cell at index. Throws if out of range. */
    abstract operator fun get(index: Int): Any?

    /** Lazy child family. Null means no children (leaf row). */
    abstract val child: Series<MiniRowVec>?

    /** True if this row carries no scalar cells -- meaning is in children. */
    val isShell: Boolean get() = size == 0
}

/** Expose the row as a Series<Any?> for uniform Cursor traversal. */
fun MiniRowVec.asSeries(): Series<Any?> = size j { this[it] }

/** Bridge into TrikeShed RowVec for columnar operations (select, valueAt, etc.).
 *  Values are the scalar cells; metas are plain ColumnMeta stubs keyed by index.
 *  Shell rows (size=0) produce an empty RowVec. */
fun MiniRowVec.toRowVec(): RowVec {
    val sz = size
    if (sz == 0) return ReifiedSplitSeries2(
        leftSeries  = 0 j { _ -> throw IndexOutOfBoundsException("empty row") },
        rightSeries = 0 j { _ -> throw IndexOutOfBoundsException("empty row") }
    )
    return ReifiedSplitSeries2(
        leftSeries  = sz j { this[it] },
        rightSeries = sz j { i -> { "col$i" j IOMemento.IoString } }
    )
}

/** Wrap any TrikeShed RowVec as a MiniRowVec — the reverse bridge.
 *  Enables ReifiedSplitSeries2.select(vararg) results to flow back into MiniCursor. */
class WrappedRowVec(val inner: RowVec, override val child: Series<MiniRowVec>? = null) : MiniRowVec() {
    override val size: Int get() = inner.a
    override fun get(index: Int): Any? = (inner as ReifiedSplitSeries2<*, *>).leftSeries[index]
}

/* ═══════════════════════════════════════════════════════════════════════════
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
 * ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Abstract base for rows that carry a lazy child family.
 *
 * The child is computed once and cached. Subclasses must call `loadChild`
 * in their `child` getter:
 *
 * ```
 *var cached: Series<MiniRowVec>? = null
 * override val child: Series<MiniRowVec>? get() = loadChild(cached) { factory() }
 * ```
 */
abstract class LazyChildRowVec : MiniRowVec() {
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
        cached: Series<MiniRowVec>?,
        factory: () -> Series<MiniRowVec>?,
    ): Series<MiniRowVec>? {
        var state = cached
        return state ?: factory()?.also { state = it }
    }
}
