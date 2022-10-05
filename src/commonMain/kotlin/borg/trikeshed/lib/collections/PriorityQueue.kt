package borg.trikeshed.lib.collections

/**
 * heap based pq with comparator
 * @param T type of elements
 * @param comparator comparator to use
 * @param initialCapacity initial capacity of the heap
 * @param growFactor factor to grow the heap by
 * @param shrinkFactor factor to shrink the heap by
 * @param shrinkThreshold threshold to shrink the heap by
 */

class PriorityQueue<T>(
    private val comparator: Comparator<T>,
    initialCapacity: Int = 16,
    private val growFactor: Double = 1.5,
    private val shrinkFactor: Double = 0.5,
    private val shrinkThreshold: Double = 0.25
) {
     var  ary: ArrayList<T> =ArrayList(initialCapacity)
    private var size = 0

    constructor(initialCapacity: Int = 16, growFactor: Double = 1.5, shrinkFactor: Double = 0.5, shrinkThreshold: Double = 0.25) : this(
        Comparator { a, b -> "$a".compareTo("$b") },
        initialCapacity,
        growFactor,
        shrinkFactor,
        shrinkThreshold
    )

    fun remove(): T {
        val e = ary[0]
        val last = ary[--size]
        if (size > 0) {
            ary[0] = last
            var i = 0
            while (true) {
                val l = i * 2 + 1
                val r = i * 2 + 2
                if (l >= size) {
                    break
                }
                val c = if (r >= size || comparator.compare(ary[l], ary[r]) < 0) l else r
                if (comparator.compare(ary[i], ary[c]) <= 0) {
                    break
                }
                ary[c] = ary[i].also { ary[i] = ary[c] }
                i = c
            }
        }
        if (size < ary.size * shrinkThreshold) ary = ArrayList(ary.subList(0, (ary.size * shrinkFactor).toInt()))
        return e
    }

    fun poll(): T? {
        return if (size > 0) remove() else null
    }

    fun element(): T {
        return ary[0]
    }

    fun peek(): T? {
        return if (size > 0) ary[0] else null
    }

    fun add(e: T) {
        if (size >= ary.size) ary = ArrayList(ary.subList(0, (ary.size * growFactor).toInt()))
        ary[size++] = e
        var i = size - 1
        while (i > 0) {
            val p = (i - 1) / 2
            if (comparator.compare(ary[p], ary[i]) <= 0) break
            ary[p] = ary[i].also { ary[i] = ary[p] }
            i = p
        }
    }
}