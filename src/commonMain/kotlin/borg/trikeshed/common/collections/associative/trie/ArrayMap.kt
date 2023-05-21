package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.common.collections.binarySearch
import kotlin.collections.Map.Entry as Map_Entry


class ArrayMap<K : Comparable<K>, V>(
    private val entre: Array<out Map_Entry<K, V>>,
    val keyComparator: Comparator<K> = naturalOrder(),
    val valComparator: Comparator<Map_Entry<K, V>> =
        Comparator<Map_Entry<K, V>> { (o1: K), (o2: K) ->
            keyComparator.compare(
                o1,
                o2
            )
        }.then { e1, e2 ->
            ("${e1.key}".compareTo("${e2.key}"))
        }
) : Map<K, V> {
    override val entries: Set<Map_Entry<K, V>>
        get() = map { (k, v) ->
            object : Map_Entry<K, V> {
                override val key get() = k
                override val value get() = v
            }
        }.toSet()
    override val size: Int get() = entre.size
    override val keys: Set<K> get() = entre.map(Map_Entry<K, *>::key).toSet()
    override val values: List<V> get() = entre.map(Map_Entry<K, V>::value)
    override fun containsKey(key1: K): Boolean = 0 <= binIndexOf(key1)

    override fun containsValue(value: V): Boolean = entre.any { (_, v) -> (v?.equals(value) ?: false) }
    private fun binIndexOf(key1: K) = entre.binarySearch(comparatorKeyShim(key1), valComparator)

    override fun get(key: K): V? = binIndexOf(key).takeIf { it >= 0 }?.let { ix -> entre[ix].value }

    fun comparatorKeyShim(key: K): Map_Entry<K, V> = ShimEntry(key)

    override fun isEmpty(): Boolean = run(entre::isEmpty)

    companion object {


        /**
         * if there aren't guarantees about ordered constructor entries, we can do a quick sort first on the comparator
         */
        fun <K : Comparable<K>, V> sorting(
            map: Map<K, V>,
            cmp: Comparator<K> = naturalOrder(),
            valComparator: Comparator<Map_Entry<K, V>> =
                compareBy { it.key },
        ): ArrayMap<K, V> {
            val entre = map.entries.toTypedArray()
            entre.sortWith(valComparator)
            return ArrayMap(entre, cmp, valComparator)
        }
    }
}

class ShimEntry<K, V>(private val key1: K) : Map_Entry<K, V> {
    override val key: K get() = key1
    override val value: V get() = TODO("Not yet implemented")
}
