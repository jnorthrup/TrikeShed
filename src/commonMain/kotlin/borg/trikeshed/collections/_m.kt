package borg.trikeshed.collections

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.α
import borg.trikeshed.lib.toList

/**
 * missing stdlib map convenience operator
 */
object _m {
    operator fun <K, V, P : Pair<K, V>> get(p: List<P>): Map<K, V> = (p).toMap()
    operator fun <K, V, P : Pair<K, V>> get(vararg p: P): Map<K, V> = mapOf(*p)

    /** The house spelling: `_m[a j b, c j d]` — Join varargs, the CCEK-native pair. */
    operator fun <K, V> get(vararg p: Join<K, V>): Map<K, V> = LinkedHashMap<K, V>(p.size).apply {
        for (j in p) put(j.a, j.b)
    }
    operator fun <K, V, P : Join<K, V>, T : Pair<K, V>> get(p: Series<P>): Map<K, V> = _m[((p α { it.pair as T }).toList())]
}
