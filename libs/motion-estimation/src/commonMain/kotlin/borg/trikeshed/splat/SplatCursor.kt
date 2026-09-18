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

// ── Cursor integration - minimal stubs ──────────────────────────
// These are placeholders - full implementation requires fixing j/α type inference

fun Series<RowVec>.toSplatSeries(): Series<Splat> = this as Series<Splat>
fun Series<Splat>.toSplatSet(): Cursor = this as Cursor
fun Splat.toRowVec(): RowVec = this as RowVec
fun splatSetOf(vararg splats: Splat): Cursor = splats[0] as Cursor
fun RowVec.toSplat(): Splat = this as Splat