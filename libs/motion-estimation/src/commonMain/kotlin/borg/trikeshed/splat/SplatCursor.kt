package borg.trikeshed.splat

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Cursor integration for Splat — makes Splat a first-class RowVec in the cursor algebra.
 * This enables all Cursor operations (where, project, α, faceting, join, combine, Grid)
 * on SplatSet without modifying the core cursor algebra.
 */

fun Splat.toRowVec(): RowVec = 
    (2 j { i: Int -> 
        when (i) {
            0 -> this@Splat
            1 -> { ColumnMeta("splat", IOMemento.IoObject, null) }
        }
    }) as RowVec

/**
 * Create a SplatSet (Cursor of Splat rows) from a list of Splats.
 * Each row carries its own metadata supplier per cursor algebra conventions.
 */
fun splatSetOf(vararg splats: Splat): Cursor = 
    splats.map { it.toRowVec() }.size j { i -> this[i] }

/**
 * Create a SplatSet from a Series of Splats.
 */
fun Series<Splat>.toSplatSet(): Cursor = 
    this α { it.toRowVec() }

/**
 * Extract the Splat value from a RowVec (row 0 of the split series).
 */
fun RowVec.toSplat(): Splat = 
    this[0] as Splat

/**
 * Project a SplatSet to a Series of Splat values (drops metadata).
 */
fun Cursor.toSplatSeries(): Series<Splat> = 
    this α { it.toSplat() }