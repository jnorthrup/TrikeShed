class HashTable<K, V>(private val capacity: Int = 16) {
    private class Entry<K, V>(val key: K, var value: V, var next: Entry<K, V>?)

    private val buckets: Array<Entry<K, V>?> = arrayOfNulls(capacity)
    private var size = 0

    fun put(key: K, value: V) {
        val index = indexFor(hash(key))
        var current = buckets[index]

        if (current == null) {
            buckets[index] = Entry(key, value, null)
            size++
            return
        }

        var prev: Entry<K, V>? = null
        while (current != null) {
            if (current.key == key) {
                current.value = value
                return
            }
            prev = current
            current = current.next
        }

        prev?.next = Entry(key, value, null)
        size++
    }

    fun get(key: K): V? {
        val index = indexFor(hash(key))
        var current = buckets[index]

        while (current != null) {
            if (current.key == key) {
                return current.value
            }
            current = current.next
        }

        return null
    }

    fun remove(key: K): V? {
        val index = indexFor(hash(key))
        var current = buckets[index]
        var prev: Entry<K, V>? = null

        while (current != null) {
            if (current.key == key) {
                if (prev == null) {
                    buckets[index] = current.next
                } else {
                    prev.next = current.next
                }
                size--
                return current.value
            }
            prev = current
            current = current.next
        }

        return null
    }

    fun size(): Int {
        return size
    }

    private fun hash(key: K): Int {
        return key?.hashCode() ?: 0
    }

    private fun indexFor(hash: Int): Int {
        return (hash and 0x7FFFFFFF) % capacity
    }
}