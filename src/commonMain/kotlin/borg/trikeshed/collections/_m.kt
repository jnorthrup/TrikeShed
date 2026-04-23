@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α

/**
 * missing stdlib map convenience operator
 */
object _m {
    operator fun <K, V, P : Pair<K, V>> get(p: List<P>): Map<K, V> = (p).toMap()
    operator fun <K, V, P : Pair<K, V>> get(vararg p: P): Map<K, V> = mapOf(*p)
    operator fun <K, V, P : Join<K, V>, T : Pair<K, V>> get(p: Series<P>): Map<K, V> = _m[((p α { it.pair as T }).toList())]
}

