package borg.trikeshed.hermes

import borg.trikeshed.lib.*
import borg.trikeshed.vm.Teleported
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class HermesLeaseRetirementTest {
    @Test fun ownerRemainsLiveUntilAdmittedCrossingAndServicesRetire() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val first = HermesPythonPort(install = { guest ->
            guest.delegate("hold") {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                Teleported.Bool(true)
            }
        })
        val observer = HermesPythonPort()
        val source = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        val crossing = Thread {
            try { first.evalInVm("host.call('hold')") }
            catch (problem: Throwable) { failure.compareAndSet(null, problem) }
        }.apply { isDaemon = true }
        val retirement = Thread {
            try { first.quiesce() }
            catch (problem: Throwable) { failure.compareAndSet(null, problem) }
        }.apply { isDaemon = true }
        try {
            first.importInVm(first.inventorySources(source), "entry")
            observer.importInVm(observer.inventorySources(source), "entry")
            val owner = first.evalInVm("import trikeshed_host\ntrikeshed_host.call('vfs_lease', 'owner', '')") as Teleported.Str
            fun alive() = observer.evalInVm("import trikeshed_host\ntrikeshed_host.call('vfs_lease', 'owner_alive', '${owner.v}')")
            assertEquals(Teleported.Bool(true), alive())
            crossing.start()
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            retirement.start()
            val deadline = java.lang.System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (first.vmStarted && java.lang.System.nanoTime() < deadline) Thread.yield()
            assertFalse(first.vmStarted, "retirement did not stop guest admission")
            assertTrue(retirement.isAlive, "retirement did not wait for the admitted crossing")
            assertEquals(Teleported.Bool(true), alive(), "a quiescing owner still holds its effects")
            release.countDown()
            crossing.join(30_000)
            retirement.join(30_000)
            assertFalse(crossing.isAlive)
            assertFalse(retirement.isAlive)
            failure.get()?.let { throw it }
            assertEquals(Teleported.Bool(true), alive(), "services still own their leases after quiescence")
            first.close()
            assertEquals(Teleported.Bool(false), alive(), "closed services no longer own leases")
        } finally {
            release.countDown()
            if (crossing.state != Thread.State.NEW) crossing.join(30_000)
            if (retirement.state != Thread.State.NEW) retirement.join(30_000)
            if (!crossing.isAlive && !retirement.isAlive) first.close()
            observer.close()
        }
    }
}
