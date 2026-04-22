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
