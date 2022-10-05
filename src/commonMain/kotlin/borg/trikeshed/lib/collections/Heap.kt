package borg.trikeshed.lib.collections

class Heap<T> {
    private val list = mutableListOf<T>()
    private val comparator: Comparator<T>

    constructor() {
        comparator = Comparator { a, b -> "$a".compareTo("$b") }
    }

    constructor(comparator: Comparator<T>) {
        this.comparator = comparator
    }

    fun add(e: T) {
        list.add(e)
        var i = list.size - 1
        while (i > 0) {
            val p = (i - 1) / 2
            if (comparator.compare(list[p], list[i]) <= 0) break
            list[p] = list[i].also { list[i] = list[p] }
            i = p
        }
    }


    fun remove(): T {
        val e = list[0]
        val last = list.removeAt(list.size - 1)
        if (list.size > 0) {
            list[0] = last
            var i = 0
            while (true) {
                val l = i * 2 + 1
                val r = i * 2 + 2
                if (l >= list.size) {
                    break
                }
                val c = if (r >= list.size || comparator.compare(list[l], list[r]) < 0) l else r
                if (comparator.compare(list[i], list[c]) <= 0) {
                    break
                }
                list[c] = list[i].also { list[i] = list[c] }
                i = c
            }
        }
        return e
    }

    fun poll(): T? {
        return if (list.size > 0) remove() else null
    }

    fun element(): T {
        return list[0]
    }

    fun peek(): T? {
        return if (list.size > 0) list[0] else null
    }

    fun size(): Int {
        return list.size
    }

    fun isEmpty(): Boolean {
        return list.size == 0
    }

    fun clear() {
        list.clear()
    }
}