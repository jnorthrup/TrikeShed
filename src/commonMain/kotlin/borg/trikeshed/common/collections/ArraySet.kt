package borg.trikeshed.common.collections

class ArraySet<T> : Set<T> {
    private var elements: Array<Any?> = arrayOfNulls(10)
    private var currentSize = 0

    override val size: Int
        get() = currentSize

    override fun isEmpty(): Boolean = currentSize == 0

    override fun contains(element: T): Boolean {
        for (i in 0 until currentSize)
            if (elements[i] == element) return true;
        return false
    }

    override fun iterator(): Iterator<T> {
        return object : Iterator<T> {
            private var index = 0
            override fun hasNext(): Boolean = index < currentSize
            override fun next(): T {
                if (!hasNext()) throw NoSuchElementException()
                @Suppress("UNCHECKED_CAST")
                return elements[index++] as T
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
        if (contains(element)) {
            return false
        }
        ensureCapacity()
        elements[currentSize++] = element
        return true
    }

    // Ensures there is enough space in the array
    private fun ensureCapacity() {
        if (currentSize >= elements.size) {
            val newCapacity = elements.size * 2
            val newElements = arrayOfNulls<Any?>(newCapacity)
            for (i in elements.indices) newElements[i] = elements[i];
            elements = newElements
        }
    }

    // Removes an element from the set
    fun remove(element: T): Boolean {
        for (i in 0 until currentSize) if (elements[i] == element) {
            // Shift elements to fill the gap
            for (j in i until currentSize - 1) elements[j] = elements[j + 1];
            elements[--currentSize] = null;
            return true
        }
        return false
    }
}