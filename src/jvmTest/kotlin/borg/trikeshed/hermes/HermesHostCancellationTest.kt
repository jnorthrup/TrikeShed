package borg.trikeshed.hermes

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.*
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HermesHostCancellationTest {
    @Test
    fun watchdogUnwindsABlockingPenDelegateAndLeavesPythonLocksUsable() {
        val board = ConfixBlackboard.empty()
        val blocking = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val guest = GraalBtrfsSupervisor(
            id = "hermes-host-cancel-${System.nanoTime()}",
            facet = VmFacet.GRAAL_PYTHON,
            budget = Budget(statements = 0, wallMillis = 2_000),
        )
        HermesPen(board, modelMuxProvider = {
            if (!blocking.get()) error("provider unavailable")
            entered.countDown()
            try {
                awaitCancellation()
            } finally {
                drained.countDown()
            }
        }).install(guest)
        var running: Thread? = null
        try {
            // A provider failure remains ordinary, receipted JSON at the real guest boundary.
            assertEquals(Teleported.Bool(true), guest.eval("""
                import json, threading
                result = json.loads(host.call('mux_catalog'))
                assert result['verdict'] == 'error'
                assert result['detail'] == 'provider unavailable'
                lock = threading.RLock()
                True
            """.trimIndent(), "hermes-host-provider-error.py"))
            val receipt = board.get("hermes/python/pen/mux_catalog/1") as Map<*, *>
            assertEquals(false, receipt["ok"])
            assertEquals("error", receipt["verdict"])

            blocking.set(true)
            running = Thread({
                try {
                    guest.eval("host.call('mux_catalog')", "hermes-host-watchdog.py")
                } catch (thrown: Throwable) {
                    failure.set(thrown)
                } finally {
                    done.countDown()
                }
            }, "hermes-host-watchdog-test").apply { isDaemon = true; start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "host delegate was not entered")
            assertTrue(done.await(8, TimeUnit.SECONDS), "watchdog did not unwind the host crossing")
            assertTrue(drained.await(1, TimeUnit.SECONDS), "cancelled host coroutine did not drain")
            val interrupted = assertIs<GuestException>(failure.get())
            assertEquals(GuestFailure.INTERRUPTED, interrupted.kind)
            assertTrue(guest.isAlive)
            assertEquals(null, board.get("hermes/python/pen/mux_catalog/2"), "cancellation became a JSON result")
            guest.guest.settle()
            assertEquals(Teleported.Num(42), guest.eval("""
                with lock:
                    result = 40 + 2
                result
            """.trimIndent(), "hermes-host-after-interrupt.py"))
        } finally {
            // A broken regression must fail in bounded time, not block the test runner in close().
            if (running == null || done.count == 0L) guest.close()
        }
    }
}
