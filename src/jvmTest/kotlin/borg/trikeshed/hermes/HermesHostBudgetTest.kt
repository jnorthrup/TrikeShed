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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HermesHostBudgetTest {
    @Test
    fun boundedHostWaitCanExceedGuestBudgetAndGuestLoopStillStops() {
        val guest = GraalBtrfsSupervisor(
            id = "hermes-host-budget-${System.nanoTime()}",
            facet = VmFacet.GRAAL_PYTHON,
            budget = Budget(statements = 0, wallMillis = 500),
        )
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var running = false
        try {
            guest.delegate("wait", 2_000) {
                Thread.sleep(850)
                Teleported.Str("completed")
            }
            assertFailsWith<IllegalArgumentException> { guest.delegate("invalid", 0) { Teleported.Null } }
            assertEquals(Teleported.Str("completed"), guest.eval("host.call('wait')", "host-wait.py"))
            running = true
            Thread({
                try {
                    guest.eval("host.call('wait')\ni=0\nwhile True:\n    i+=1", "host-wait-then-loop.py")
                } catch (thrown: Throwable) {
                    failure.set(thrown)
                } finally {
                    finished.countDown()
                }
            }, "hermes-host-budget-test").apply { isDaemon = true; start() }
            assertTrue(finished.await(6, TimeUnit.SECONDS), "host lease disabled the guest execution budget")
            assertEquals(GuestFailure.INTERRUPTED, assertIs<GuestException>(failure.get()).kind)
            assertTrue(guest.isAlive)
            assertEquals(Teleported.Num(42), guest.eval("40+2", "after-host-budget.py"))
        } finally {
            if (!running || finished.count == 0L) guest.close()
        }
    }

    @Test
    fun hostLeaseDeadlineStopsABlockingDelegateIndependentlyOfGuestBudget() {
        val guest = InProcessIsolate(
            id = "hermes-host-deadline-${System.nanoTime()}",
            facet = VmFacet.GRAAL_PYTHON,
            budget = Budget(statements = 0, wallMillis = 10_000),
        )
        val entered = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        guest.delegate("block", 250) {
            entered.countDown()
            try {
                CountDownLatch(1).await()
                Teleported.Null
            } finally {
                drained.countDown()
            }
        }
        Thread({
            try {
                guest.eval("host.call('block')", "host-deadline.py")
            } catch (thrown: Throwable) {
                failure.set(thrown)
            } finally {
                finished.countDown()
            }
        }, "hermes-host-deadline-test").apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "host delegate was not entered")
            assertTrue(finished.await(4, TimeUnit.SECONDS), "host wait ran until the longer guest deadline")
            assertTrue(drained.await(1, TimeUnit.SECONDS), "host wait did not drain")
            assertEquals(GuestFailure.INTERRUPTED, assertIs<GuestException>(failure.get()).kind)
            assertTrue(guest.isAlive)
            assertEquals(Teleported.Num(42), guest.eval("40+2"))
        } finally {
            if (finished.count == 0L) guest.close()
        }
    }

    @Test
    fun sameOwnerGuestCallbackKeepsTheRemainingGuestBudgetInsideAHostLease() {
        val guest = InProcessIsolate(
            id = "hermes-host-callback-${System.nanoTime()}",
            facet = VmFacet.GRAAL_PYTHON,
            budget = Budget(statements = 0, wallMillis = 500),
        )
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var running = false
        try {
            guest.eval("def callback(value): return value+1\ndef spin():\n    while True:\n        pass")
            guest.delegate("callback", 5_000) { args -> guest.call("callback", args.single()) }
            assertEquals(Teleported.Num(42), guest.eval("host.call('callback', 41)"))
            guest.delegate("spin", 5_000) { guest.call("spin") }
            running = true
            Thread({
                try {
                    guest.eval("host.call('spin')", "host-reentrant-callback.py")
                } catch (thrown: Throwable) {
                    failure.set(thrown)
                } finally {
                    finished.countDown()
                }
            }, "hermes-host-callback-test").apply { isDaemon = true; start() }
            assertTrue(finished.await(3, TimeUnit.SECONDS), "guest callback borrowed the longer host deadline")
            assertEquals(GuestFailure.INTERRUPTED, assertIs<GuestException>(failure.get()).kind)
            assertTrue(guest.isAlive)
            assertEquals(Teleported.Num(42), guest.eval("40+2"))
        } finally {
            if (!running || finished.count == 0L) guest.close()
        }
    }
}
