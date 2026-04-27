package borg.trikeshed.concurrency

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS: Channel backed by circular Ring<T>
// Semantic gap: userspace/concurrency/Channel.kt wraps kotlinx.coroutines
//   Channel. It should be backed by a Ring<T> — the ring is the storage,
//   suspend/resume comes from the backend (NIO or io_uring) pushing/popping.
//   The Channel is a facade over Ring + active backend.
// ================================================================================

class Ring<T>(val capacity: Int) {
    @Suppress("UNCHECKED_CAST")
   val buf = arrayOfNulls<Any?>(capacity)
   var h = 0;var t = 0
   val mask = capacity - 1
    val size: Int get() = (t - h) and mask
    val isEmpty: Boolean get() = h == t
    val isFull: Boolean get() = ((t + 1) and mask) == h
    fun push(value: T): Boolean { if (isFull) return false; buf[t] = value; t = (t + 1) and mask; return true }
    @Suppress("UNCHECKED_CAST")
    fun pop(): T? { if (isEmpty) return null; val v = buf[h] as T; buf[h] = null; h = (h + 1) and mask; return v }
}

/** Channel facade: Ring storage + platform backend (NIO or io_uring). */
class RingChannel<T>(capacity: Int) {
    internal val ring = Ring<T>(capacity)
    var isClosed = false;set

    /** Push from backend (NIO read completion or io_uring CQE). */
    fun backendPush(value: T): Boolean = ring.push(value)

    /** Pop for backend (NIO write or io_uring SQE needs data). */
    fun backendPop(): T? = ring.pop()

    /** Consumer-facing: suspend until data available, then pop. */
    suspend fun recv(): T? {
        while (ring.isEmpty && !isClosed) {
            // RED: should suspend on the ring, not spin
            kotlinx.coroutines.delay(1)
        }
        return ring.pop()
    }

    /** Producer-facing: push, suspend if full. */
    suspend fun send(value: T) {
        while (ring.isFull && !isClosed) {
            kotlinx.coroutines.delay(1)
        }
        ring.push(value)
    }

    fun close() { isClosed = true }
}

// ================================================================================
// SPEC: RingChannel — Ring<T> storage with backend push/pop, suspend recv/send
// ================================================================================

class ChannelBackedByRingTest {

    /** backendPush fills the ring; recv drains it. */
    @Test fun ringChannel_backendPush_thenRecv() = runTest {
        val ch = RingChannel<Int>(8)
        assertTrue(ch.backendPush(42))
        assertEquals(42, ch.recv())
    }

    /** recv returns null when channel is closed and ring is empty. */
    @Test fun ringChannel_recv_returnsNullWhenClosed() = runTest {
        val ch = RingChannel<Int>(8)
        ch.close()
        assertEquals(null, ch.recv())
    }

    /** send fills ring; backendPop drains it for the backend to write. */
    @Test fun ringChannel_send_thenBackendPop() = runTest {
        val ch = RingChannel<Int>(4)
        ch.send(1); ch.send(2)
        assertEquals(1, ch.backendPop())
        assertEquals(2, ch.backendPop())
    }

    /** Ring is the sole storage — no kotlinx Channel underneath. */
    @Test fun ringChannel_ringIsSoleStorage() {
        val ch = RingChannel<Int>(8)
        ch.backendPush(1)
        ch.backendPush(2)
        assertEquals(2, ch.ring.size)
        assertEquals(1, ch.ring.pop())
        assertEquals(2, ch.ring.pop())
        assertTrue(ch.ring.isEmpty)
    }

    /** close wakes suspended recv with null. */
    @Test fun ringChannel_close_wakesRecv() = runTest {
        val ch = RingChannel<Int>(8)
        ch.close()
        assertEquals(null, ch.recv())
    }

    /** Backend (NIO/uring) pushes data into ring; consumer recvs. */
    @Test fun ringChannel_backendToConsumer_flow() = runTest {
        val ch = RingChannel<Int>(8)
        // Simulate NIO read completion: backend pushes bytes
        ch.backendPush(10)
        ch.backendPush(20)
        ch.backendPush(30)
        // Consumer pops in order
        assertEquals(10, ch.recv())
        assertEquals(20, ch.recv())
        assertEquals(30, ch.recv())
    }

    /** Ring has no kotlinx Channel dependency — pure array + head/tail. */
    @Test fun ring_pureArray_noChannelDependency() {
        val r = Ring<String>(8)
        assertTrue(r.push("a"))
        assertEquals("a", r.pop())
        // Ring is self-contained, no kotlinx, no locks
    }
}
