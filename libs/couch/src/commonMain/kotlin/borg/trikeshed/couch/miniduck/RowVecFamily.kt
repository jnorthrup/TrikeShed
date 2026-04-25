package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*

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

/* ═══════════════════════════════════════════════════════════════════════════
 * Lazy child infrastructure.
 *
 * Seven RowVec families share the same lazy-loading pattern for `child`:
 *   DocRowVec   — constructor param, no caching (children come from parent)
 *   ViewRowVec  — double-checked loading + caching via private var
 *   BlobRowVec  — factory function called on every child access (no caching)
 *   JsonRowVec  — same: factory called every access (no caching)
 *   YamlRowVec  — same: factory called every access (no caching)
 *   ManifoldConcept — fixed single-element child (no loader, no caching)
 *   BlockRowVec — fixed child built from internal mutable list (no factory)
 *
 * `loadChild` unifies the caching variant (ViewRowVec).
 * Subclasses that want caching implement:
 *   private var cached: Series<MiniRowVec>? = null
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
 * private var cached: Series<MiniRowVec>? = null
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
