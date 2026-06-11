package borg.trikeshed.splat

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.ConfixIndex
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.reify

// ── Cursor integration ──────────────────────────────────────────
fun Splat.toRowVec(): RowVec = (2 j { i: Int -> when (i) { 0 -> this@Splat; 1 -> { borg.trikeshed.cursor.ColumnMeta("splat", borg.trikeshed.cursor.IOMemento.IoObject, null) } } }) as RowVec

fun splatSetOf(vararg splats: Splat): Cursor = splats α { it.toRowVec() } .let { rows -> rows.size j { i -> rows[i] } }

fun Series<Splat>.toSplatSet(): Cursor = this α { it.toRowVec() }

fun RowVec.toSplat(): Splat = this[0] as Splat

fun Cursor.toSplatSeries(): Series<Splat> = this α { it.toSplat() }