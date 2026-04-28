@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

import borg.trikeshed.collections.s_

/**
 * A MutableSeries supporting O(1) addFirst and addLast.
 *
 * Backed by two [Series]: `front` (stored in reverse order) and `back`
 * (stored forward). The read path stitches: front elements are accessed
 * in reverse, back elements are accessed forward.
 *
 * addFirst → prepend to front via `s_[item] + front` (view, O(1))
 * addLast  → append to back via `back + s_[item]` (view, O(1))
 *
 * set/removeAt operate on the combined index.
 */
class DequeSeries<T> : MutableSeries<T> {

   var front: Series<T> = 0 j { throw IndexOutOfBoundsException("empty Deque front") }
   var back: Series<T>  = 0 j { throw IndexOutOfBoundsException("empty Deque back") }

    override val a: Int get() = front.size + back.size

    override val b: (Int) -> T = { i ->
        if (i < front.size) {
            // Front is reversed — element at index i is front[size-1-i]
            front[front.size - 1 - i]
        } else {
            back[i - front.size]
        }
    }

    fun addFirst(item: T) {
        front = s_[item] + front
    }

    fun addLast(item: T) {
        back = back + s_[item]
    }

    fun removeFirst(): T {
        if (front.size > 0) {
            val item = front[0]
            front = if (front.size == 1) {
                0 j { throw IndexOutOfBoundsException("empty Deque front") }
            } else {
                (front.size - 1) j { i -> front[i + 1] }
            }
            return item
        }
        // Front empty, take from back
        val item = back[0]
        back = if (back.size == 1) {
            0 j { throw IndexOutOfBoundsException("empty Deque back") }
        } else {
            (back.size - 1) j { i -> back[i + 1] }
        }
        return item
    }

    fun removeLast(): T {
        if (back.size > 0) {
            val item = back[back.size - 1]
            back = if (back.size == 1) {
                0 j { throw IndexOutOfBoundsException("empty Deque back") }
            } else {
                (back.size - 1) j { i -> back[i] }
            }
            return item
        }
        // Back empty, take from front
        val item = front[front.size - 1]
        front = if (front.size == 1) {
            0 j { throw IndexOutOfBoundsException("empty Deque front") }
        } else {
            (front.size - 1) j { i -> front[i] }
        }
        return item
    }

    // ── MutableSeries interface ──────────────────────────────────────────

    override fun add(item: T) = addLast(item)

    override fun add(index: Int, item: T) {
        if (index == 0) { addFirst(item); return }
        if (index == a) { addLast(item); return }
        // Insert in middle: rebuild the affected side
        if (index < front.size) {
            // Insert into front
            val revIdx = front.size - 1 - index
            front = (front.size + 1) j { i ->
                when {
                    i <= revIdx -> front[i]
                    i == revIdx + 1 -> item
                    else -> front[i - 1]
                }
            }
        } else {
            val backIdx = index - front.size
            back = (back.size + 1) j { i ->
                when {
                    i < backIdx -> back[i]
                    i == backIdx -> item
                    else -> back[i - 1]
                }
            }
        }
    }

    override fun set(index: Int, item: T) {
        if (index < front.size) {
            val revIdx = front.size - 1 - index
            front = front.size j { i ->
                if (i == revIdx) item else front[i]
            }
        } else {
            val backIdx = index - front.size
            back = back.size j { i ->
                if (i == backIdx) item else back[i]
            }
        }
    }

    override fun removeAt(index: Int): T {
        val item = b(index)
        if (index < front.size) {
            val revIdx = front.size - 1 - index
            front = (front.size - 1) j { i ->
                if (i < revIdx) front[i] else front[i + 1]
            }
        } else {
            val backIdx = index - front.size
            back = (back.size - 1) j { i ->
                if (i < backIdx) back[i] else back[i + 1]
            }
        }
        return item
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until a) {
            if (b(i) == item) {
                removeAt(i)
                return true
            }
        }
        return false
    }

    override fun clear() {
        front = 0 j { throw IndexOutOfBoundsException("empty Deque front") }
        back  = 0 j { throw IndexOutOfBoundsException("empty Deque back") }
    }

    override fun plus(item: T): MutableSeries<T> { addLast(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }
    override fun plusAssign(item: T) { addLast(item) }
    override fun minusAssign(item: T) { remove(item) }
}
