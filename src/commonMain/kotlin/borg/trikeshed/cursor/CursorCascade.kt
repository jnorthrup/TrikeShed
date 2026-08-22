package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import borg.trikeshed.lib.cascade.*

/*
 * ── Cursor ⇄ Cascade ─────────────────────────────────────────────────────────────────────────
 *
 * A Cursor column holding a prefix-ordered key is a Couch view waiting to be queried.
 * The key is cell.toString(), one Char per symbol; null → empty. Key<Char> from there on.
 *
 *   cursor.emits(ColK.ByName("key")) { row -> value }   map()   : Series<Emit<Char,V>>
 *   cursor.groupLevel(key, depth, m) {..}        ?group_level=k : Cursor [prefix, value]
 *   cursor.prefixRange(key, "S_S_6")             startkey/endkey: Cursor (rows kept)
 *   cursor.view(key, m) {..}                     the zoom slider: (Depth) -> Cursor
 */

/** Read a facet as a Key<Char>: cell.toString(), one Char per symbol; null → empty. */
fun RowVec.charKey(key: ColK<*>): Key<Char> = (asFaceted()[key]?.toString() ?: "").toSeries()

/** map(): one Emit per row. The key column is the signature; the value is anything bounded. */
inline fun <V> Cursor.emits(key: ColK<*>, crossinline value: (RowVec) -> V): Series<Emit<Char, V>> =
    this α { row -> row.charKey(key) j value(row) }

/** startkey=p, endkey=p{ — rows whose key begins with [p]. */
fun Cursor.prefixRange(key: ColK<*>, p: CharSequence): Cursor = filter { it.charKey(key).startsWith(p.toString()) }

/** Couch `?group_level=depth` over a Cursor — delivery-time fold. */
inline fun <V> Cursor.groupLevel(key: ColK<*>, depth: Depth, m: Monoid<V>, crossinline value: (RowVec) -> V): Cursor =
    emits(key, value).groupLevel(depth, m).toCursor()

/** The zoom slider: any depth is one fold returning a Cursor. */
inline fun <V> Cursor.view(key: ColK<*>, m: Monoid<V>, crossinline value: (RowVec) -> V): (Depth) -> Cursor =
    { depth -> groupLevel(key, depth, m, value) }

/** Load a Cursor into a Trie (values with equal keys combine through [m]) for cached-partial queries. */
inline fun <V> Cursor.toTrie(key: ColK<*>, m: Monoid<V>, crossinline value: (RowVec) -> V): Trie<Char, V> =
    Trie<Char, V>(m).also { t -> for (i in 0 until size) { val row = this[i]; t.add(row.charKey(key), value(row)) } }

/** A Level rendered as a two-column Cursor: [prefix: String, name: V]. */
fun <V> Level<Char, V>.toCursor(name: String = "value"): Cursor = size j { i ->
    val node = this[i]
    cellsToRowVec(
        cells = seriesOfAny(listOf(node.a.toList().joinToString(""), node.b)),
        keys = listOf("prefix", name).toSeries(),
    )
}
