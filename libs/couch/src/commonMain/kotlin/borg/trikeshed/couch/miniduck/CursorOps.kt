package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*

/** MiniDuck cursor: a lazy indexed Series of MiniRowVec. */
typealias MiniCursor = Series<MiniRowVec>

/** Empty cursor — zero-size; any index access throws. */
fun emptyMiniCursor(): MiniCursor = 0 j { _: Int -> throw IndexOutOfBoundsException("empty cursor") }

// ── Row access (canonical) ────────────────────────────────────────────────────
// cursor[i] (single Int) falls through to Series.get(Int) = b(i) = row — the
// intentional schism with column-slicing operators.  .at() / .row() are the
// explicit named aliases that make the intent clear at call sites.

/** Row access by index. Negative index counts from the end. */
infix fun MiniCursor.at(y: Int): MiniRowVec = b(if (y < 0) size + y else y)

/** Alias for [at]. */
infix fun MiniCursor.row(y: Int): MiniRowVec = at(y)

// ── Column-slicing operators ──────────────────────────────────────────────────
// These never return a single MiniRowVec; they always return a MiniCursor (the
// factory semantics that separate them from Series defaults).
//
// NOTE: operator get(vararg Int) is deliberately NOT defined here.
// Adding it on Series<MiniRowVec> would shadow Series<T>.get(Int) for single-Int
// calls (more-specific receiver wins in Kotlin), breaking cursor[i] row access.
// Use .columns(vararg Int) for index projection and .at(i) for rows.

/** Project columns by positional index vararg. First row's DocRowVec keys are used for naming. */
fun MiniCursor.columns(vararg colIdx: Int): MiniCursor {
    if (size == 0) return emptyMiniCursor()
    val exemplar = at(0)
    val projectedKeys: List<String> = colIdx.map { i ->
        (exemplar as? DocRowVec)?.keys?.getOrNull(i) ?: i.toString()
    }
    return size j { rowIdx ->
        val r = at(rowIdx)
        DocRowVec(
            keys = projectedKeys,
            cells = projectedKeys.map { r.getValue(it) },
            child = (r as? DocRowVec)?.child,
        )
    }
}

/** Project columns by name vararg — `cursor["name","age"]`. No Int conflict: Series has no get(String). */
operator fun MiniCursor.get(vararg names: String): MiniCursor = project(*names)

/** Exclude a column by name: `cursor - "debug"`. */
operator fun MiniCursor.minus(name: String): MiniCursor {
    if (size == 0) return emptyMiniCursor()
    val exemplar = at(0) as? DocRowVec ?: return this
    val retained = exemplar.keys.filter { it != name }
    return project(*retained.toTypedArray())
}

// ── Row operations ────────────────────────────────────────────────────────────

/**
 * Filter rows matching [pred].
 *
 * Single pass collects matching indices into an IntArray, then builds
 * a lazy index-addressed Series — avoids double-evaluating deferred
 * row content (ViewRowVec.docLoader, child factories, etc.).
 */
fun MiniCursor.where(pred: Predicate): MiniCursor {
    val matching = ArrayList<Int>(size)
    for (i in 0 until size) if (pred.matches(at(i))) matching.add(i)
    val idx = matching.toIntArray()
    return idx.size j { at(idx[it]) }
}

/** Take the first [n] rows — pure lazy view, no allocation. */
fun MiniCursor.take(n: Int): MiniCursor {
    require(n >= 0) { "take requires n >= 0, got $n" }
    return minOf(n, size) j { at(it) }
}

/** Drop the first [n] rows — pure lazy view, no allocation. */
fun MiniCursor.drop(n: Int): MiniCursor {
    require(n >= 0) { "drop requires n >= 0, got $n" }
    return maxOf(0, size - n) j { at(n + it) }
}

/** Sort by a single [key]. Nulls sort before non-nulls; [desc] reverses. */
fun MiniCursor.orderBy(key: String, desc: Boolean = false): MiniCursor {
    val mul = if (desc) -1 else 1
    val idx = (0 until size)
        .sortedWith { a, b -> mul * compareKeys(at(a).getValue(key), at(b).getValue(key)) }
        .toIntArray()
    return idx.size j { at(idx[it]) }
}

/** Ordering specification for multi-key sorts. */
data class OrderSpec(val key: String, val desc: Boolean = false)

/** Sort by multiple keys. Earlier specs take priority. */
fun MiniCursor.orderBy(vararg specs: OrderSpec): MiniCursor {
    val idx = (0 until size).sortedWith { a, b ->
        for (spec in specs) {
            val mul = if (spec.desc) -1 else 1
            val cmp = mul * compareKeys(at(a).getValue(spec.key), at(b).getValue(spec.key))
            if (cmp != 0) return@sortedWith cmp
        }
        0
    }.toIntArray()
    return idx.size j { at(idx[it]) }
}

/**
 * Project: select named [keys] into DocRowVec rows.
 *
 * Values are extracted via [getValue]. Missing keys produce null cells.
 * Child hierarchy is preserved when the source row is a DocRowVec.
 */
fun MiniCursor.project(vararg keys: String): MiniCursor = size j { rowIdx ->
    val row = at(rowIdx)
    DocRowVec(
        keys = keys.toList(),
        cells = keys.map { row.getValue(it) },
        child = (row as? DocRowVec)?.child,
    )
}

/** Infix aliases for dense DSL chains. */
infix fun MiniCursor.filter(pred: Predicate): MiniCursor = where(pred)
infix fun MiniCursor.limit(n: Int): MiniCursor = take(n)
infix fun MiniCursor.then(transform: (MiniCursor) -> MiniCursor): MiniCursor = transform(this)
