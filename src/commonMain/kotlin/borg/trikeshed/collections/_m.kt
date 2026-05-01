package borg.trikeshed.collections

/**
 * missing stdlib map convenience operator
 */
object _m {
    operator fun <K, V, P : Pair<K, V>> get(p: List<P>): Map<K, V> = (p).toMap()
    operator fun <K, V, P : Pair<K, V>> get(vararg p: P): Map<K, V> = mapOf(*p)
    operator fun <K, V, P : Join<K, V>, T : Pair<K, V>> get(p: Series<P>): Map<K, V> = _m[((p α { it.pair as T }).toList())]
}

