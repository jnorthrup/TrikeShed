package borg.trikeshed.hermes

import borg.trikeshed.graal.subvm.*
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HermesHostFinalizerCancellationTest {
    @Test
    fun interruptTimeoutCancelsTheContextBeforePythonFinalizerLockCanSpin() {
        val entered = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val guest = InProcessIsolate(
            id = "hermes-finalizer-${System.nanoTime()}",
            facet = VmFacet.GRAAL_PYTHON,
            budget = Budget(statements = 0, wallMillis = 1_500),
        )
        guest.delegate("block") {
            entered.countDown()
            try {
                CountDownLatch(1).await()
                Teleported.Null
            } catch (interrupted: InterruptedException) {
                // Real host cleanup can outlast the soft-interrupt grace. No interrupt bits
                // are changed: a hard cancellation can interrupt this cleanup wait as well.
                Thread.sleep(InProcessIsolate.INTERRUPT_GRACE_MS + 1_000)
                throw interrupted
            } finally {
                drained.countDown()
            }
        }
        var running = false
        try {
            guest.eval("import threading\nlock = threading.RLock()")
            running = true
            Thread({
                try {
                    guest.eval("""
                        try:
                            host.call('block')
                        finally:
                            with lock:
                                cleanup = True
                    """.trimIndent(), "hermes-interrupted-finalizer.py")
                } catch (thrown: Throwable) {
                    failure.set(thrown)
                } finally {
                    finished.countDown()
                }
            }, "hermes-finalizer-test").apply { isDaemon = true; start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "blocking host delegate was not entered")
            assertTrue(finished.await(8, TimeUnit.SECONDS), "timed-out soft interrupt left Python running")
            assertTrue(drained.await(1, TimeUnit.SECONDS), "host callback did not drain")
            assertEquals(GuestFailure.DEAD, assertIs<GuestException>(failure.get()).kind)
            assertFalse(guest.isAlive, "timed-out context remained reusable")
            assertEquals(GuestFailure.DEAD, assertFailsWith<GuestException> { guest.eval("1+1") }.kind)
            Thread({
                try { guest.close() } finally { closed.countDown() }
            }, "hermes-finalizer-close-test").apply { isDaemon = true; start() }
            assertTrue(closed.await(5, TimeUnit.SECONDS), "cancelled context close did not return")
        } finally {
            // Keep the regression bounded if an engine failure prevents the crossing from ending.
            if (!running || finished.count == 0L) guest.close()
        }
    }
}
