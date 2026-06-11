package borg.trikeshed.splat

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import borg.trikeshed.parse.confix.ConfixDoc

// ── Operations ───────────────────────────────────────────────────
typealias SplatSet = Cursor

fun <T> Series<T>.filter(predicate: (T) -> Boolean): Series<T> {
    val indices = mutableListOf<Int>(); (0 until size).forEach { if (predicate(this[it])) indices.add(it) }
    return indices.size.j { idx -> this[indices[idx]] }
}

fun SplatSet.merge(other: SplatSet): Pair<SplatSet, List<SplatEvent>> {
    val events = mutableListOf<SplatEvent>()
    val thisMap = this.toSplatSeries().associateBy { it.id }
    val otherMap = other.toSplatSeries().associateBy { it.id }
    val allIds = (thisMap.keys + otherMap.keys).toSet()
    
    val mergedSeries: Series<Splat> = allIds α { id ->
        val thisSplat = thisMap[id]; val otherSplat = otherMap[id]
        when {
            thisSplat != null && otherSplat != null -> {
                val base = if (thisSplat.opacity >= otherSplat.opacity) thisSplat else otherSplat
                val merged = base.withAttributes(otherSplat.attributes.with("velocity", otherSplat.attributes.get<Series<Double>>("velocity")))
                events.add(SplatUpdated(id, merged.attributes, captureNanos()))
                return@α merged
            }
            thisSplat != null -> return@α thisSplat
            else -> return@α otherSplat!!
        }
    }
    return mergedSeries.toSplatSet() to events
}

fun SplatSet.cull(predicate: (Splat) -> Boolean): Pair<SplatSet, List<SplatEvent>> {
    val events = mutableListOf<SplatEvent>()
    val kept = this.toSplatSeries().filter { !predicate(it) }
    val culled = this.toSplatSeries().filter { predicate(it) }
    for (splat in culled) {
        events.add(SplatCulled(splat.id, "predicate", captureNanos()))
    }
    return kept.toSplatSet() to events
}

fun SplatSet.applyMotion(delta: Series<Double>, version: Long): List<SplatEvent> {
    val series: Series<Splat> = this.toSplatSeries(); val events = mutableListOf<SplatEvent>()
    for (i in 0 until series.size) { events.add(series[i].applyMotion(delta, version)) }
    return events
}