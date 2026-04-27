package borg.trikeshed.concurrency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS: Circular ring buffer on commonMain
// Semantic gap: userspace/concurrency/Channel.kt wraps kotlinx.coroutines
//   Channel. It should be a fixed-capacity circular ring buffer with push/pop
//   that can be backed by NIO or io_uring underneath.
// Design: Ring<T>(capacity: Int) — contiguous array, head/tail pointers,
//   mask-based wrap. Single-producer single-consumer safe without locks.
// ================================================================================

class Ring<T>(val capacity: Int) {
    @Suppress("UNCHECKED_CAST")
    private val buf = arrayOfNulls<Any?>(capacity)
    private var head = 0
    private var tail = 0
    private val mask = capacity - 1 // capacity must be power of 2

    val size: Int get() = (tail - head) and mask
    val isEmpty: Boolean get() = head == tail
    val isFull: Boolean get() = ((tail + 1) and mask) == head

    fun push(value: T): Boolean {
        if (isFull) return false
        buf[tail] = value
        tail = (tail + 1) and mask
        return true
    }

    @Suppress("UNCHECKED_CAST")
    fun pop(): T? {
        if (isEmpty) return null
        val value = buf[head] as T
        buf[head] = null // release reference
        head = (head + 1) and mask
        return value
    }

    fun clear() {
        while (head != tail) {
            buf[head] = null
            head = (head + 1) and mask
        }
    }
}

// ================================================================================
// SPEC: Ring buffer with mask-based wrap, fixed capacity, push/pop
// ================================================================================

class CircularRingRedTest {

    /** Ring starts empty. */
    @Test fun ring_startsEmpty() {
        val r = Ring<Int>(8)
        assertTrue(r.isEmpty)
        assertEquals(0, r.size)
        assertFalse(r.isFull)
    }

    /** Push adds element, size increments. */
    @Test fun ring_push_incrementsSize() {
        val r = Ring<Int>(8)
        assertTrue(r.push(42))
        assertEquals(1, r.size)
        assertFalse(r.isEmpty)
    }

    /** Pop returns FIFO order. */
    @Test fun ring_pop_fifo() {
        val r = Ring<Int>(8)
        r.push(1); r.push(2); r.push(3)
        assertEquals(1, r.pop())
        assertEquals(2, r.pop())
        assertEquals(3, r.pop())
        assertTrue(r.isEmpty)
    }

    /** Push fails when full (one slot reserved for empty/full disambiguation). */
    @Test fun ring_push_failsWhenFull() {
        val r = Ring<Int>(4) // capacity 4, usable slots: 3
        assertTrue(r.push(1))
        assertTrue(r.push(2))
        assertTrue(r.push(3))
        assertTrue(r.isFull)
        assertFalse(r.push(4)) // rejected
        assertEquals(3, r.size)
    }

    /** Pop on empty returns null. */
    @Test fun ring_pop_emptyReturnsNull() {
        val r = Ring<Int>(8)
        assertNull(r.pop())
    }

    /** Wrap-around: head and tail wrap at mask boundary. */
    @Test fun ring_wrapAround() {
        val r = Ring<Int>(4) // mask = 3
        r.push(1); r.push(2); r.push(3)
        r.pop(); r.pop() // head at 2
        assertTrue(r.push(4))
        assertTrue(r.push(5))
        // tail wrapped: 3 then 0
        assertEquals(3, r.size)
        assertEquals(3, r.pop())
        assertEquals(4, r.pop())
        assertEquals(5, r.pop())
        assertTrue(r.isEmpty)
    }

    /** Clear resets to empty, releases references. */
    @Test fun ring_clear_resets() {
        val r = Ring<Int>(8)
        r.push(1); r.push(2); r.push(3)
        r.clear()
        assertTrue(r.isEmpty)
        assertEquals(0, r.size)
        assertNull(r.pop())
    }

    /** Capacity must be power of 2 for mask-based arithmetic. */
    @Test fun ring_capacity_mustBePowerOfTwo() {
        // This test documents the constraint; the stub doesn't enforce it.
        val r = Ring<Int>(8)
        assertEquals(7, r.mask) // 8 - 1 = 0b111
        assertEquals(8, r.capacity)
    }
}
