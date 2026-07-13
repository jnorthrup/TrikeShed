package borg.trikeshed.lib

class SeriesBuffer<T>(
    capacity: Int = 8,
) : Series<T> {
    var buf: Array<Any?> = arrayOfNulls(capacity)
    var count: Int = 0

    override val a: Int get() = count
    override val b: (Int) -> T get() = { index ->
        @Suppress("UNCHECKED_CAST")
        buf[index] as T
    }

    fun add(item: T) {
        if (count == buf.size) {
            val next = arrayOfNulls<Any?>(buf.size * 2)
            buf.copyInto(next)
            buf = next
        }
        buf[count++] = item
    }

    fun removeLast(): T {
        require(count > 0) { "removeLast on empty SeriesBuffer" }
        val idx = --count
        @Suppress("UNCHECKED_CAST")
        val item = buf[idx] as T
        buf[idx] = null
        return item
    }

    fun snapshot(): Series<T> {
        val currentBuf = buf.copyOf()
        val currentCount = count
        return currentCount j { index ->
            @Suppress("UNCHECKED_CAST")
            currentBuf[index] as T
        }
    }
}
