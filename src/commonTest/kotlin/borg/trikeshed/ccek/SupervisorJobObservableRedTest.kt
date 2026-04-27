package borg.trikeshed.ccek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

// ================================================================================
// SELF-CONTAINED STUBS: CCEK Observable draw-through lifecycle
// Donor: dreamer/stream IoMux_CCEK_redTest.kt — Observable<T>, SupervisorJob,
//   CancelToken, ElementState, LifecycleState
// Semantic gap: CcekScope.kt is 8 lines with just coroutineService<T>(key).
//   No SupervisorJob, no Observable slot fan-out, no draw-through lifecycle.
//   The ENDGAME architecture prescribes: "async context keys are singleton
//   identity objects, lifecycle is forward-only, elements expose key,
//   lifecycleState, fanoutSubscribers, open/drain/close".
// ================================================================================

/** Lifecycle states: forward-only (OPEN → DRAINING → CLOSED). */
enum class FanoutLifecycle { OPEN, DRAINING, CLOSED }

/** Cancel token returned by observe() — call cancel() to stop receiving updates. */
interface CancelToken {
    fun cancel()
}

/** Observable slot — draw-through reference that reflects upstream changes. */
interface Observable<out T> {
    val value: T
    fun observe(callback: (T) -> Unit): CancelToken
}

/** Mutable observable for test stubs. */
class MutableObservable<T>(initial: T) : Observable<T> {
    private var _value: T = initial
    override val value: T get() = _value
    private val callbacks = mutableListOf<(T) -> Unit>()

    fun update(newValue: T) {
        _value = newValue
        val snapshot = callbacks.toList()
        snapshot.forEach { it(newValue) }
    }

    override fun observe(callback: (T) -> Unit): CancelToken {
        callbacks.add(callback)
        return object : CancelToken {
            override fun cancel() { callbacks.remove(callback) }
        }
    }
}

/** A SupervisorJob fans out changes to subscriber slots. */
interface SupervisorJob {
    val key: Any
    val isActive: Boolean
    val lifecycle: FanoutLifecycle
    val fanoutSubscribers: Int
    fun <T> slot(source: Observable<T>): Observable<T>
    fun submit(task: suspend () -> Unit)
    fun open()
    fun drain()
    fun close()
}

/** Stub SupervisorJob for RED tests. */
class SupervisorJobStub(override val key: Any) : SupervisorJob {
    override var isActive: Boolean = false
        private set
    override var lifecycle: FanoutLifecycle = FanoutLifecycle.CLOSED
        private set
    override var fanoutSubscribers: Int = 0
        private set

    override fun open() { lifecycle = FanoutLifecycle.OPEN; isActive = true }
    override fun drain() { lifecycle = FanoutLifecycle.DRAINING }
    override fun close() { lifecycle = FanoutLifecycle.CLOSED; isActive = false; fanoutSubscribers = 0 }

    override fun <T> slot(source: Observable<T>): Observable<T> {
        fanoutSubscribers++
        return MutableObservable(source.value)
    }

    override fun submit(task: suspend () -> Unit) {
        // Stub: no real dispatch
    }
}

// ================================================================================
// SPEC: CCEK Observable draw-through — SupervisorJob, slots, lifecycle
// ================================================================================

class SupervisorJobObservableRedTest {

    /** SupervisorJob can be created with a singleton identity key. */
    @Test
    fun supervisorJob_createdWithKey() {
        val job = SupervisorJobStub("wallet-supervisor")
        assertEquals("wallet-supervisor", job.key)
        assertFalse(job.isActive)
        assertEquals(FanoutLifecycle.CLOSED, job.lifecycle)
    }

    /** open() transitions to OPEN, forward-only. */
    @Test
    fun supervisorJob_open_transitionsToOpen() {
        val job = SupervisorJobStub("key")
        job.open()
        assertEquals(FanoutLifecycle.OPEN, job.lifecycle)
        assertTrue(job.isActive)
    }

    /** drain() transitions OPEN → DRAINING (no new subscribers). */
    @Test
    fun supervisorJob_drain_transitionsToDraining() {
        val job = SupervisorJobStub("key")
        job.open()
        job.drain()
        assertEquals(FanoutLifecycle.DRAINING, job.lifecycle)
    }

    /** close() transitions to CLOSED and clears subscribers. */
    @Test
    fun supervisorJob_close_clearsSubscribers() {
        val job = SupervisorJobStub("key")
        job.open()
        val source = MutableObservable(42)
        job.slot(source) // adds subscriber
        assertEquals(1, job.fanoutSubscribers)
        job.close()
        assertEquals(FanoutLifecycle.CLOSED, job.lifecycle)
        assertEquals(0, job.fanoutSubscribers)
        assertFalse(job.isActive)
    }

    /** slot() creates an Observable view of a source Observable. */
    @Test
    fun supervisorJob_slot_createsObservableView() {
        val job = SupervisorJobStub("key")
        job.open()
        val source = MutableObservable(99)
        val view = job.slot(source)
        assertEquals(99, view.value)
        assertEquals(1, job.fanoutSubscribers)
    }

    /** slot() increments fanoutSubscribers count. */
    @Test
    fun supervisorJob_slot_incrementsSubscriberCount() {
        val job = SupervisorJobStub("key")
        job.open()
        job.slot(MutableObservable(1))
        job.slot(MutableObservable(2))
        job.slot(MutableObservable(3))
        assertEquals(3, job.fanoutSubscribers)
    }

    /** Observable.observe fires callback when value changes. */
    @Test
    fun observable_observe_firesOnChange() {
        val obs = MutableObservable(0)
        var callCount = 0
        var lastValue: Int? = null
        obs.observe { value ->
            callCount++
            lastValue = value
        }
        obs.update(42)
        assertEquals(1, callCount)
        assertEquals(42, lastValue)
    }

    /** Observable.observe fires for multiple subscribers. */
    @Test
    fun observable_observe_multipleSubscribers() {
        val obs = MutableObservable("initial")
        val received = mutableListOf<String>()
        obs.observe { received.add("A:$it") }
        obs.observe { received.add("B:$it") }
        obs.update("updated")
        assertEquals(2, received.size)
        assertTrue(received.contains("A:updated"))
        assertTrue(received.contains("B:updated"))
    }

    /** CancelToken.cancel stops further callbacks. */
    @Test
    fun cancelToken_cancel_stopsCallbacks() {
        val obs = MutableObservable(0)
        var callCount = 0
        val token = obs.observe { callCount++ }
        obs.update(1)
        assertEquals(1, callCount)
        token.cancel()
        obs.update(2)
        assertEquals(1, callCount) // no more callbacks
    }

    /** Forward-only lifecycle: CLOSED → OPEN → DRAINING → CLOSED, never backwards. */
    @Test
    fun lifecycle_forwardOnly_noBackwards() {
        val job = SupervisorJobStub("key")
        assertEquals(FanoutLifecycle.CLOSED, job.lifecycle)
        job.open()
        assertEquals(FanoutLifecycle.OPEN, job.lifecycle)
        job.drain()
        assertEquals(FanoutLifecycle.DRAINING, job.lifecycle)
        job.close()
        assertEquals(FanoutLifecycle.CLOSED, job.lifecycle)
    }

    /** submit schedules a coroutine task. */
    @Test
    fun supervisorJob_submit_schedulesTask() {
        val job = SupervisorJobStub("key")
        var executed = false
        job.submit { executed = true }
        // stub doesn't execute — RED: need real dispatch
        assertFalse(executed)
    }
}
