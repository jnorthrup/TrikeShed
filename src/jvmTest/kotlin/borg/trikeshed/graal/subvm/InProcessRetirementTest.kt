package borg.trikeshed.graal.subvm

import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class InProcessRetirementTest {
    @Test fun retirementWaitsForTheDelegateAndRefusesAQueuedCrossing() {
        val guest = InProcessIsolate("retirement", VmFacet.GRAAL_PYTHON, Budget(statements = 0, wallMillis = 0))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = AtomicReference<Throwable?>()
        val queued = AtomicReference<Throwable?>()
        val result = AtomicReference<Result<Teleported>?>()
        val finalized = AtomicReference<Teleported?>()
        val owner = Thread {
            result.set(runCatching { guest.eval("host.call('hold')", "hold.py") })
        }
        guest.delegate("hold") {
            entered.countDown()
            var interrupted = false
            while (true) try { release.await(); break } catch (_: InterruptedException) { interrupted = true }
            if (interrupted) Thread.currentThread().interrupt()
            assertEquals(Teleported.Num(42), guest.eval("42", "nested.py"))
            Teleported.Str("finished")
        }
        guest.delegate("finalize", 1_000L) { Teleported.Str("finalized") }
        val waiter = Thread { queued.set(runCatching { guest.eval("42", "queued.py") }.exceptionOrNull()) }
        val retire = Thread { closing.set(runCatching {
            guest.quiesce { finalized.set(guest.eval("host.call('finalize')", "finalize.py")) }
            guest.closeAndAwait()
        }.exceptionOrNull()) }
        try {
            owner.start()
            assertTrue(entered.await(20, TimeUnit.SECONDS), "delegate did not enter")
            waiter.start()
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (waiter.state != Thread.State.WAITING && System.nanoTime() < limit) Thread.yield()
            assertEquals(Thread.State.WAITING, waiter.state, "second crossing did not queue")
            retire.start()
            while (guest.isAlive && System.nanoTime() < limit) Thread.yield()
            assertFalse(guest.isAlive, "retirement did not stop admission")
            assertTrue(retire.isAlive, "retirement returned while the delegate was blocked")
            release.countDown()
            owner.join(20_000); waiter.join(20_000); retire.join(20_000)
            assertFalse(owner.isAlive || waiter.isAlive || retire.isAlive, "retirement did not settle")
            assertNull(closing.get())
            assertEquals(Teleported.Str("finished"), assertNotNull(result.get()).getOrThrow())
            assertEquals(Teleported.Str("finalized"), finalized.get())
            assertEquals(GuestFailure.DEAD, assertIs<GuestException>(queued.get()).kind)
        } finally {
            release.countDown()
            owner.join(20_000); waiter.join(20_000); retire.join(20_000)
            if (!retire.isAlive) guest.closeAndAwait()
        }
    }

    @Test fun aDelegateCannotRetireItsOwnCrossing() {
        val guest = InProcessIsolate("reentrant-retirement", VmFacet.GRAAL_PYTHON, Budget(statements = 0, wallMillis = 0))
        try {
            guest.delegate("retire") {
                assertFailsWith<IllegalStateException> { guest.closeAndAwait() }
                Teleported.Bool(guest.isAlive)
            }
            assertEquals(Teleported.Bool(true), guest.eval("host.call('retire')", "reentrant.py"))
            assertEquals(Teleported.Num(42), guest.eval("42", "after.py"))
        } finally { guest.closeAndAwait() }
    }
}
