package borg.trikeshed.collections

/**
 * Long keys to non-null values by open addressing: linear probing, backward-shift removal (no tombstones), keys
 * unboxed in a LongArray. Load stays at or under 3/4 of a power-of-two capacity, which only grows.
 */
class LongTable<V : Any>(expected: Int = 8) {
    private var keys: LongArray
    private var values: Array<Any?>
    private var mask: Int
    var size: Int = 0
        private set

    init {
        require(expected >= 0) { "negative expected size" }
        var capacity = 8
        while (capacity * 3L < expected * 4L) capacity = capacity shl 1
        keys = LongArray(capacity)
        values = arrayOfNulls(capacity)
        mask = capacity - 1
    }

    fun isEmpty(): Boolean = size == 0

    private fun home(key: Long): Int {
        val h = key * -0x61c8864680b583ebL
        return (h xor (h ushr 32)).toInt() and mask
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Long): V? {
        var i = home(key)
        while (true) {
            val v = values[i] ?: return null
            if (keys[i] == key) return v as V
            i = (i + 1) and mask
        }
    }

    operator fun contains(key: Long): Boolean = get(key) != null

    /** Binds [key] to [value]; the value it replaced, or null. */
    @Suppress("UNCHECKED_CAST")
    fun put(key: Long, value: V): V? {
        if ((size + 1) * 4L > values.size * 3L) grow()
        var i = home(key)
        while (true) {
            val v = values[i]
            if (v == null) {
                keys[i] = key; values[i] = value; size++
                return null
            }
            if (keys[i] == key) {
                values[i] = value
                return v as V
            }
            i = (i + 1) and mask
        }
    }

    /** Unbinds [key]; the value it held, or null. */
    @Suppress("UNCHECKED_CAST")
    fun remove(key: Long): V? {
        var i = home(key)
        while (true) {
            val v = values[i] ?: return null
            if (keys[i] == key) {
                values[i] = null
                size--
                close(i)
                return v as V
            }
            i = (i + 1) and mask
        }
    }

    /** Backward shift: entries after the hole whose home is not in (hole, j] move into it. */
    private fun close(start: Int) {
        var hole = start
        var j = (hole + 1) and mask
        while (true) {
            val v = values[j] ?: return
            val h = home(keys[j])
            val movable = if (j > hole) h <= hole || h > j else h <= hole && h > j
            if (movable) {
                keys[hole] = keys[j]; values[hole] = v; values[j] = null
                hole = j
            }
            j = (j + 1) and mask
        }
    }

    fun clear() {
        if (size == 0) return
        values.fill(null)
        size = 0
    }

    /** Every binding, in table order; [action] must not mutate this table. */
    @Suppress("UNCHECKED_CAST")
    inline fun forEach(action: (Long, V) -> Unit) {
        for (i in 0 until slots()) {
            val v = slotValue(i) ?: continue
            action(slotKey(i), v as V)
        }
    }

    @PublishedApi internal fun slots(): Int = values.size
    @PublishedApi internal fun slotKey(i: Int): Long = keys[i]
    @PublishedApi internal fun slotValue(i: Int): Any? = values[i]

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2)
        values = arrayOfNulls(oldValues.size * 2)
        mask = values.size - 1
        size = 0
        for (i in oldValues.indices) {
            val v = oldValues[i] ?: continue
            var j = home(oldKeys[i])
            while (values[j] != null) j = (j + 1) and mask
            keys[j] = oldKeys[i]; values[j] = v; size++
        }
    }
}
