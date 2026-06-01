package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ReduxListBridge — a MutableSeries delegate that intercepts every [set] call
 * and journals it through a ReduxMutableSeries. The delegate handles actual
 * storage; Redux provides the event journal and lazily-reified state.
 *
 * This makes the full emission history (every list mutation) available as:
 *   - the raw event journal (all SetAction entries)
 *   - the lazily reified state (the current List at reify-time)
 *
 * The test hammers set() with N rapid updates and verifies:
 *   1. the ReduxMutableSeries journal captures every set (journal.size == N)
 *   2. the lazily reified state reflects the last value at every index
 */
class ReduxListBridgeTest {

    /** An action representing a single list set(index, value). */
    data class SetAction<T>(val index: Int, val value: T)

    /**
     * Reducer that maintains the current list state from a stream of SetActions.
     * Every action produces an updated list with that index replaced or grown.
     */
    class ListStateReducer<T> : Reducer<SetAction<T>, List<T>> {
        override val zero: List<T> = emptyList()

        override fun combine(acc: List<T>, action: SetAction<T>): List<T> {
            // Zero-state is a Series that throws on toList() — catch and start empty
            val current: List<T> = try {
                acc.toList()
            } catch (_: Exception) {
                emptyList()
            }
            val result = ArrayList<T>(maxOf(current.size, action.index + 1))
            // Copy existing elements (or 0..size-1 = nothing if starting from empty)
            for (i in 0 until current.size) result.add(current[i])
            // Grow + fill any gap with the previous element
            while (result.size < action.index) result.add(result.last())
            // Set or append at action.index
            if (result.size == action.index) result.add(action.value)
            else result[action.index] = action.value
            return result
        }
    }

    /**
     * ReduxListBridge — delegates storage to the backing MutableSeries, tracks
     * every set() call in a ReduxMutableSeries journal.
     */
    class ReduxListBridge<T>(
        private val delegate: MutableSeries<T>,
        initialCapture: T? = null,
    ) : MutableSeries<T> by delegate {

        private val capture = SetAction(-1, initialCapture as T)

        private val redux = ReduxMutableSeries(
            eventJournal = ChunkedMutableSeries<SetAction<T>>(),
            reducer = ListStateReducer(),
            initialState = emptyList<T>(),
            capture = capture,
        )

        override fun set(index: Int, item: T) {
            redux.dispatch(SetAction(index, item))
            // State lives in ReduxMutableSeries — no need to sync to delegate.
            // Delegate only satisfies the MutableSeries interface contract.
        }

        val journal: Series<SetAction<T>> get() = redux.eventJournal
        val state: List<T> get() = redux.state
    }

    @Test
    fun `set events are journaled and state reifies correctly`() {
        val delegate = ChunkedMutableSeries<String>()
        val bridge = ReduxListBridge(delegate)

        // pre-populate 5 slots
        for (i in 0 until 5) {
            bridge.set(i, "init-$i")
        }
        assertEquals(5, bridge.journal.size, "journal should have 5 init entries")
        assertEquals(5, bridge.state.size, "state should have 5 entries after init")

        // rapid-fire abuse: 10 000 set operations, hammering every slot
        val N = 10_000
        for (i in 0 until N) {
            bridge.set(i % 5, "set-$i")
        }

        assertEquals(
            5 + N,
            bridge.journal.size,
            "journal must capture every set including init: expected ${5 + N}, got ${bridge.journal.size}"
        )

        // state: slot k gets last op where op % 5 == k and op < N
        // last such op = N - 5 + k
        val s = bridge.state
        assertEquals(5, s.size)
        for (i in 0 until 5) {
            assertEquals("set-${N - 5 + i}", s[i], "index $i")
        }
    }

    @Test
    fun `abusive concurrent-style set barrage`() {
        val delegate = ChunkedMutableSeries<Int>()
        val bridge = ReduxListBridge(delegate)

        val slots = 8
        val opsPerSlot = 1_250
        val total = slots * opsPerSlot

        for (slot in 0 until slots) {
            for (op in 0 until opsPerSlot) {
                bridge.set(slot, slot * 10_000 + op)
            }
        }

        assertEquals(total, bridge.journal.size, "every set must be in the journal")
        val s = bridge.state
        assertEquals(slots, s.size)
        for (slot in 0 until slots) {
            assertEquals(
                slot * 10_000 + (opsPerSlot - 1),
                s[slot],
                "slot $slot should hold the last value written to it"
            )
        }
    }

    @Test
    fun `journal is ordered and complete across all slots`() {
        val bridge = ReduxListBridge(ChunkedMutableSeries<Long>())

        val ops = 500
        for (i in 0 until ops) {
            bridge.set(i % 10, i.toLong())
        }

        val s = bridge.state
        assertEquals(10, s.size)
        // last ops targeting slots 0..9: 490,491,492,493,494,495,496,497,498,499
        assertEquals(490L, s[0])
        assertEquals(499L, s[9])

        // journal size = ops
        assertEquals(ops, bridge.journal.size)

        // replay the journal and reconstruct state independently
        var checkState = emptyList<Long>()
        for (ix in 0 until bridge.journal.size) {
            val a = bridge.journal[ix]
            checkState = if (a.index < checkState.size) {
                checkState.toMutableList().apply { this[a.index] = a.value }
            } else {
                val b = ArrayList(checkState)
                while (b.size < a.index) b.add(b.last())
                b.add(a.value)
                b
            }
        }
        assertEquals(s, checkState)
    }
}
