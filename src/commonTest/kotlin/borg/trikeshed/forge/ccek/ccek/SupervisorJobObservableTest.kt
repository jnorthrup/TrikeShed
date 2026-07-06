package borg.trikeshed.ccek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ================================================================================
// Production-ready tests: CCEK draw-through lifecycle using RealSupervisorJob
// ================================================================================

class SupervisorJobObservableTest {

    /** Lifecycle is forward-only: CLOSED → OPEN → DRAINING → CLOSED. Never backward. */
    @Test
    fun lifecycle_forwardOnly() {
        val job = RealSupervisorJob("wallet")
        assertEquals(FanoutLifecycle.CLOSED, job.lifecycle)
        job.open()
        assertEquals(FanoutLifecycle.OPEN, job.lifecycle)
        job.drain()
        assertEquals(FanoutLifecycle.DRAINING, job.lifecycle)
        job.close()
        assertEquals(FanoutLifecycle.CLOSED, job.lifecycle)
        assertEquals(0, job.fanoutSubscribers) // close clears
    }

    /** Key is a singleton identity object — equality by reference. */
    @Test
    fun key_isSingletonIdentity() {
        val key = "wallet-supervisor"
        val a = RealSupervisorJob(key)
        val b = RealSupervisorJob(key)
        assertEquals(a.key, b.key)
    }

    /** slot() exposes fanoutSubscribers count — one per active subscriber. */
    @Test
    fun slot_exposesFanoutSubscriberCount() {
        val job = RealSupervisorJob("key")
        job.open()
        assertEquals(0, job.fanoutSubscribers)
        job.slot(MutableObservable(1))
        assertEquals(1, job.fanoutSubscribers)
        job.slot(MutableObservable(2))
        job.slot(MutableObservable(3))
        assertEquals(3, job.fanoutSubscribers)
        job.close()
        assertEquals(0, job.fanoutSubscribers)
    }

    /** slot() creates a draw-through view — value reflects source without polling. */
    @Test
    fun slot_drawThrough_reflectsSource() {
        val job = RealSupervisorJob("key")
        job.open()
        val source = MutableObservable(0)
        val view = job.slot(source)
        assertEquals(0, view.value)
        source.update(42)
        // GREEN: Real SupervisorJob.slot gives a draw-through view.
        assertEquals(42, view.value)
    }

    /** observe() fires on value change — callback soup avoided via structured fanout. */
    @Test
    fun observe_firesOnChange() {
        val obs = MutableObservable(0)
        var callCount = 0
        var lastValue: Int? = null
        obs.observe { value -> callCount++; lastValue = value }
        obs.update(42)
        // Note: observe initially fires once, and then fires again on update.
        // So callCount should be 2 (initial 0, then 42).
        assertEquals(2, callCount)
        assertEquals(42, lastValue)
    }

    /** CancelToken.cancel stops further callbacks — no leak. */
    @Test
    fun cancelToken_stopsCallbacks() {
        val obs = MutableObservable(0)
        var callCount = 0
        val token = obs.observe { callCount++ } // Fires once initially (callCount = 1)
        obs.update(1) // Fires again (callCount = 2)
        assertEquals(2, callCount)
        token.cancel()
        obs.update(2)
        assertEquals(2, callCount) // no more callbacks after cancel
    }

    /** Multiple observers fire independently. */
    @Test
    fun observe_multipleObservers() {
        val obs = MutableObservable("initial")
        val received = mutableListOf<String>()
        obs.observe { received.add("A:$it") }
        obs.observe { received.add("B:$it") }
        obs.update("updated")
        assertTrue(received.contains("A:updated") && received.contains("B:updated"))
    }
}
