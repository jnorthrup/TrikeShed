package vec.macros

import java.util.*

inline fun <reified V> Map<Int, V>.sparseVect0r(): SparseVect0r<V> = run {
    val entries =
        ((this as? SortedMap)?.entries ?: entries.sortedBy { it.key }).toList()
    val sparse: Series<V?> = bindSparse(entries α { (k, v) -> k t2 v })
    SparseVect0r(sparse, entries)
}

inline fun <reified V> bindSparse(
    driver: Vect02<Int, V>,
): Series<V?> = driver.let { entres ->
    val k = Vect02_(driver).left.toIntArray()
    k.size t2 if (driver.size <= 16) {
        { x: Int ->
            var r: V? = null
            if (driver.size > 0) {
                var i = 0
                do {
                    if (k[i] == x)
                        r = Vect02_(entres).right[i]
                    ++i
                } while (i < entres.size && r == null)
            }
            r
        }
    } else { x: Int ->
        k.binarySearch(x).takeUnless {
            0 > it
        }?.let { i -> Vect02_(entres).right[i] }
    }
}

class SparseVect0r<V>(
    private val sparse: Series<V?>,
    private val entries: List<Map.Entry<Int, V>>,
) : Series<V?> by sparse, Iterable<Map.Entry<Int, V>> by (entries) {
    val left: Series<Int> get() = entries α Map.Entry<Int, V>::key
    val right: Series<V> get() = entries α Map.Entry<Int, V>::value
    val keys: Series<Int> by this::left
    val values: Series<V> by this::right
}
