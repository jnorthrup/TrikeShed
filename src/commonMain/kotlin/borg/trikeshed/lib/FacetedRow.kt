package borg.trikeshed.lib

/**
 * FacetedRow — the universal row type.
 *
 * Any MetaSeries<K extends OpK<*>, Any?> is a FacetedRow.
 * CharStr, RowVec, ManifoldRow, and CCEK Element are all FacetedRow
 * with different key families.
 */
typealias FacetedRow<K> = MetaSeries<K, Any?>

/** GADT-safe accessor — one unchecked cast at the dispatch boundary. */
@Suppress("UNCHECKED_CAST")
inline operator fun <K : OpK<R>, R> FacetedRow<K>.get(op: K): R = b(op) as R
