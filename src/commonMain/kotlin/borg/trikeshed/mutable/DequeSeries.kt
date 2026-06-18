package borg.trikeshed.mutable

import borg.trikeshed.collections.s_
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.plus
import borg.trikeshed.lib.size

/**
 * A MutableSeries supporting O(1) addFirst and addLast.
 *
 * Backed by two [borg.trikeshed.lib.Series]: `front` (stored in reverse order) and `back`
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
   var back: Series<T> = 0 j { throw IndexOutOfBoundsException("empty Deque back") }

    override val a: Int get() = front.size + back.size

    override val b: (Int) -> T = { i ->
        if (i < front.size) {
            front[front.size - 1 - i]
        } else {
            back[i - front.size]
        }
    }

    fun addFirst(item: T) {
        front = front + s_[item]
    }

    fun addLast(item: T) {
        back = back + s_[item]
    }

    fun removeFirst(): T {
        if (front.size > 0) {
            val item = front[front.size - 1]
            val oldFront = front
            front = if (oldFront.size == 1) {
                0 j { throw IndexOutOfBoundsException("empty Deque front") }
            } else {
                (oldFront.size - 1) j { i -> oldFront[i] }
            }
            return item
        }
        val item = back[0]
        val oldBack = back
        back = if (oldBack.size == 1) {
            0 j { throw IndexOutOfBoundsException("empty Deque back") }
        } else {
            (oldBack.size - 1) j { i -> oldBack[i + 1] }
        }
        return item
    }

    fun removeLast(): T {
        if (back.size > 0) {
            val item = back[back.size - 1]
            val oldBack = back
            back = if (oldBack.size == 1) {
                0 j { throw IndexOutOfBoundsException("empty Deque back") }
            } else {
                (oldBack.size - 1) j { i -> oldBack[i] }
            }
            return item
        }
        val item = front[0]
        val oldFront = front
        front = if (oldFront.size == 1) {
            0 j { throw IndexOutOfBoundsException("empty Deque front") }
        } else {
            (oldFront.size - 1) j { i -> oldFront[i + 1] }
        }
        return item
    }

    // ── MutableSeries interface ──────────────────────────────────────────

    override fun add(item: T) = addLast(item)

    override fun add(index: Int, item: T) {
        if (index == 0) { addFirst(item); return }
        if (index == a) { addLast(item); return }
        if (index < front.size) {
            val revIdx = front.size - 1 - index
            val oldFront = front
            front = (oldFront.size + 1) j { i ->
                when {
                    i <= revIdx -> oldFront[i]
                    i == revIdx + 1 -> item
                    else -> oldFront[i - 1]
                }
            }
        } else {
            val backIdx = index - front.size
            val oldBack = back
            back = (oldBack.size + 1) j { i ->
                when {
                    i < backIdx -> oldBack[i]
                    i == backIdx -> item
                    else -> oldBack[i - 1]
                }
            }
        }
    }

    override fun set(index: Int, item: T) {
        if (index < front.size) {
            val revIdx = front.size - 1 - index
            val oldFront = front
            front = oldFront.size j { i ->
                if (i == revIdx) item else oldFront[i]
            }
        } else {
            val backIdx = index - front.size
            val oldBack = back
            back = oldBack.size j { i ->
                if (i == backIdx) item else oldBack[i]
            }
        }
    }

    override fun removeAt(index: Int): T {
        val item = b(index)
        if (index < front.size) {
            val revIdx = front.size - 1 - index
            val oldFront = front
            front = (oldFront.size - 1) j { i ->
                if (i < revIdx) oldFront[i] else oldFront[i + 1]
            }
        } else {
            val backIdx = index - front.size
            val oldBack = back
            back = (oldBack.size - 1) j { i ->
                if (i < backIdx) oldBack[i] else oldBack[i + 1]
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