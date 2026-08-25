package borg.trikeshed.cursor

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import kotlin.jvm.JvmInline

/**
 * The PRELOAD.md fancy-indexing operator grammar over [Cursor], as thin delegates to the
 * named combinators in CursorOps (which stay the explicit-dispatch layer):
 *
 *   cursor[i]                row (generic `Series<T>.get(Int)` — already the algebra)
 *   cursor[i0 until i1]      row range view (generic `Series<T>.get(IntRange)`, lazy)
 *   cursor[1, 3, 2]          column reorder / projection by ordinal (two-or-more ints,
 *                            so a single int keeps its row meaning)
 *   cursor["name", "age"]    column projection by name
 *   cursor[-"debug"]         column exclusion
 *
 * Widening/concatenation stay the named `join(l, r)` / `combine(t, b)` — an operator for
 * those would hide which axis grows.
 */

/** Column-exclusion marker for `cursor[-"debug"]` — packed, per the zero-cost taxonomy mandate. */
@JvmInline
value class ColumnExclusion(val name: CharSequence)

operator fun CharSequence.unaryMinus(): ColumnExclusion = ColumnExclusion(this)

/** `cursor[1, 3, 2]` — column reorder / projection by ordinal. */
operator fun Cursor.get(c0: Int, c1: Int, vararg more: Int): Cursor = select(c0, c1, *more)

/** `cursor["name", "age"]` — column projection by name. */
operator fun Cursor.get(vararg names: CharSequence): Cursor = select(*names)

/** `cursor[-"debug"]` — column exclusion by name. */
operator fun Cursor.get(excluded: ColumnExclusion): Cursor = without(excluded.name)
