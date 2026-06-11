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

// ── Minimal stubs to avoid type inference issues ──────────────────
// These are needed by other modules but cause type inference issues when fully implemented
// Full implementation requires fixing the j/α type inference in the library

fun Series<RowVec>.toSplatSeries(): Series<Splat> = this as Series<Splat>
fun Series<Splat>.toSplatSet(): Cursor = this as Cursor
fun Splat.toRowVec(): RowVec = this as RowVec
fun splatSetOf(vararg splats: Splat): Cursor = splats[0] as Cursor
fun Series<Splat>.toSplatSeries(): Series<Splat> = this
fun RowVec.toSplat(): Splat = this as Splat