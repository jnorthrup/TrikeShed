@file:Suppress("unused")

package borg.trikeshed.cascade

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.size

/* ── Cascade — Cursor-based Cascading Continuums ─────────────────── *
 * libs/cascade/ expresses N-level cascading groupBy projections as pure Cursor algebra.
 * Each view is a different eigenbasis of the same readings tensor.
 * The reduce monoid (StatsReduce) is the invariant inner product.
 *
 * Modules:
 *   Readings       — RowVec schema, column ordinals, date axis decomposition
 *   StatsReduce    — Commutative monoid {sum, avg, min, max, count} as RowReducer
 *   CascadeViews   — N Cursor.groupBy projections over key columns
 *   WatermarkCursor — Append-only MutableSeries with high-water mark
 *   IsAOwnership   — TypeSubsumption lattice for hierarchical key grouping
 *
 * Versioned handle-body volatiles as cascade lazy signals:
 *   - WatermarkCursor is the stable handle
 *   - ArrayList<RowVec> is the volatile body (swapped atomically on batch commit)
 *   - Watermark (Long) is the monotonic version
 *   - Delegate is sent in to provide new rows
 *   - Confix re-join at element parent reconstructs Cursor from volatile body
 */

val CASCADE_VERSION = "0.1.0-SNAPSHOT"

// ── Re-export Join helpers as cascade package members (transitive API) ──

/** Empty series of T — zero elements, never accessed. */
@Suppress("UNCHECKED_CAST")
fun <T> emptySeriesOf(): Series<T> =
    object : Join<Int, (Int) -> T> {
        override val a: Int get() = 0
        override val b: (Int) -> T get() = { _: Int -> throw IllegalStateException("empty series") }
    } as Series<T>

// NOTE: joins is defined in Join.kt (line 37) — use that directly.
// cascade.kt does NOT re-declare it to avoid receiver shadowing in lambdas.