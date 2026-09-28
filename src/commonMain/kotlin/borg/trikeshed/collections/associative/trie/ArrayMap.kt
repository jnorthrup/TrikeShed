package borg.trikeshed.collections.associative.trie

import borg.trikeshed.collections.binarySearch
import borg.trikeshed.collections.FunnelHashMap
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
    override fun containsKey(key: K): Boolean = 0 <= binIndexOf(key)

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
    override val value: V get() = throw UnsupportedOperationException("ShimEntry is used only for key comparison")
}

/**
 * A read-only map over parallel key and value arrays, in insertion order. Lookup is a linear scan
 * while the map is small ([LINEAR] entries or fewer), where a scan beats any index; past that the
 * first lookup indexes key → slot once in a [FunnelHashMap].
 */
class LinearArrayMap<K : Any, V>(private val keyArray: Array<K>, private val valueArray: Array<V>) : AbstractMap<K, V>() {
    init { require(keyArray.size == valueArray.size) }
    private var bySlot: FunnelHashMap<K, Int>? = null

    override val size: Int get() = keyArray.size
    override fun isEmpty(): Boolean = keyArray.isEmpty()

    private fun slot(key: Any?): Int {
        val n = keyArray.size
        if (n <= LINEAR) { for (i in 0 until n) if (keyArray[i] == key) return i; return -1 }
        if (key == null) return -1
        val index = bySlot ?: FunnelHashMap<K, Int>(n).also { m -> for (i in n - 1 downTo 0) m.put(keyArray[i], i); bySlot = m }
        @Suppress("UNCHECKED_CAST")
        return index.get(key as K) ?: -1
    }

    override fun get(key: K): V? = slot(key).let { if (it < 0) null else valueArray[it] }
    override fun containsKey(key: K): Boolean = slot(key) >= 0
    override fun containsValue(value: V): Boolean = valueArray.any { it == value }
    override val keys: Set<K> get() = object : AbstractSet<K>() {
        override val size get() = keyArray.size
        override fun iterator() = keyArray.iterator()
        override fun contains(element: K) = slot(element) >= 0
    }
    override val values: Collection<V> get() = valueArray.asList()
    override val entries: Set<Map_Entry<K, V>> get() = object : AbstractSet<Map_Entry<K, V>>() {
        override val size get() = keyArray.size
        override fun iterator() = object : Iterator<Map_Entry<K, V>> {
            var i = 0
            override fun hasNext() = i < keyArray.size
            override fun next(): Map_Entry<K, V> { val k = keyArray[i]; val v = valueArray[i]; i++
                return object : Map_Entry<K, V> { override val key get() = k; override val value get() = v } }
        }
    }

    companion object { const val LINEAR = 8 }
}
