package borg.trikeshed.splat

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import borg.trikeshed.parse.confix.ConfixDoc

typealias SplatSet = Cursor

// Minimal stub - all logic removed to verify core types
fun SplatSet.merge(other: SplatSet): Pair<SplatSet, List<SplatEvent>> = Pair(this, mutableListOf())
fun SplatSet.cull(predicate: (Splat) -> Boolean): Pair<SplatSet, List<SplatEvent>> = Pair(this, mutableListOf())
fun SplatSet.applyMotion(delta: Series<Double>, version: Long): List<SplatEvent> = mutableListOf()