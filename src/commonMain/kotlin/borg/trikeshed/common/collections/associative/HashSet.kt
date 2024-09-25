package borg.trikeshed.common.collections.associative

class HashSet<T> : Set<T> {
    private var buckets: Array<Any?> = arrayOfNulls(16)
    private var flags: IntArray = IntArray(16) // 0 = empty, 1 = occupied, 2 = deleted
    private var currentSize = 0

    override val size: Int
        get() = currentSize

    override fun isEmpty(): Boolean = currentSize == 0

    override fun contains(element: T): Boolean {
        val hash = element.hashCode()
        var index = indexFor(hash)
        val originalIndex = index

        while (flags[index] != 0) {
            if (flags[index] == 1 && buckets[index] == element) {
                return true
            }
            index = (index + 1) % buckets.size
            if (index == originalIndex) {
                break
            }
        }
        return false
    }

    override fun iterator(): Iterator<T> {
        return object : Iterator<T> {
            private var index = -1
            private fun findNext() {
                index++
                while (index < buckets.size && flags[index] != 1) {
                    index++
                }
            }

            override fun hasNext(): Boolean {
                var tempIndex = index + 1
                while (tempIndex < buckets.size && flags[tempIndex] != 1) {
                    tempIndex++
                }
                return tempIndex < buckets.size
            }

            override fun next(): T {
                findNext()
                if (index >= buckets.size) throw NoSuchElementException()
                @Suppress("UNCHECKED_CAST")
                return buckets[index] as T
            }
        }
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        for (element in elements) {
            if (!contains(element)) {
                return false
            }
        }
        return true
    }

    // Adds an element to the set
    fun add(element: T): Boolean {
        if (currentSize >= buckets.size * 0.75) {
            resize()
        }
        val hash = element.hashCode()
        var index = indexFor(hash)
        val originalIndex = index

        while (flags[index] == 1) {
            if (buckets[index] == element) {
                return false
            }
            index = (index + 1) % buckets.size
            if (index == originalIndex) {
                return false // Table is full
            }
        }
        buckets[index] = element
        flags[index] = 1
        currentSize++
        return true
    }

    // Removes an element from the set
    fun remove(element: T): Boolean {
        val hash = element.hashCode()
        var index = indexFor(hash)
        val originalIndex = index

        while (flags[index] != 0) {
            if (flags[index] == 1 && buckets[index] == element) {
                flags[index] = 2 // Mark as deleted
                buckets[index] = null
                currentSize--
                return true
            }
            index = (index + 1) % buckets.size
            if (index == originalIndex) {
                break
            }
        }
        return false
    }

    // Computes the index in the array for a given hash
    private fun indexFor(hash: Int): Int {
        return (hash and 0x7FFFFFFF) % buckets.size
    }

    // Resizes the internal array when load factor exceeds threshold
    private fun resize() {
        val oldBuckets = buckets
        val oldFlags = flags
        buckets = arrayOfNulls(buckets.size * 2)
        flags = IntArray(flags.size * 2)
        currentSize = 0

        for (i in oldBuckets.indices) {
            if (oldFlags[i] == 1) {
                @Suppress("UNCHECKED_CAST")
                add(oldBuckets[i] as T)
            }
        }
    }
}

fun main() {
    val arraySet = ArraySet<Int>()
    arraySet.add(10)
    arraySet.add(20)
    arraySet.add(30)
    println("ArraySet contains 20: ${arraySet.contains(20)}") // Output: true
    arraySet.remove(20)
    println("ArraySet contains 20 after removal: ${arraySet.contains(20)}") // Output: false

    val hashSet = HashSet<String>()
    hashSet.add("apple")
    hashSet.add("banana")
    hashSet.add("cherry")
    println("HashSet contains 'banana': ${hashSet.contains("banana")}") // Output: true
    hashSet.remove("banana")
    println("HashSet contains 'banana' after removal: ${hashSet.contains("banana")}") // Output: false
}