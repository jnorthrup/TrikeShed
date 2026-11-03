package borg.trikeshed.hermes

import borg.trikeshed.lib.*
import borg.trikeshed.vm.Teleported
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class HermesPortRetirementTest {
    @Test fun guestCannotWaitForPortRetirementWhileRetirementJoinsGuest() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        lateinit var port: HermesPythonPort
        port = HermesPythonPort(install = { guest ->
            guest.delegate("nested_close") {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                assertFailsWith<IllegalStateException> { port.close() }
                Teleported.Bool(true)
            }
        })
        val inventory = port.inventorySources(_m["entry" j (("entry.py" j "READY = True\n").pair)])
        port.importInVm(inventory, "entry")
        val crossing = Thread {
            try { port.evalInVm("host.call('nested_close')") }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
        }.apply { isDaemon = true }
        val closer = Thread {
            try { port.close() }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
        }.apply { isDaemon = true }
        try {
            crossing.start()
            assertTrue(entered.await(30, TimeUnit.SECONDS), "guest did not enter delegate")
            closer.start()
            val deadline = java.lang.System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (port.vmStarted && java.lang.System.nanoTime() < deadline) Thread.yield()
            assertFalse(port.vmStarted, "retirement did not close admission")
            release.countDown()
            crossing.join(30_000)
            closer.join(30_000)
            assertFalse(crossing.isAlive, "guest crossing deadlocked on retirement")
            assertFalse(closer.isAlive, "retirement deadlocked on guest crossing")
            failure.get()?.let { throw it }
        } finally {
            release.countDown()
            crossing.join(1_000)
            if (closer.state != Thread.State.NEW) closer.join(1_000)
            if (!crossing.isAlive && !closer.isAlive) port.close()
        }
    }

    @Test fun importCannotCloseItsOwnPortDuringInstallation() {
        lateinit var port: HermesPythonPort
        port = HermesPythonPort(install = { assertFailsWith<IllegalStateException> { port.close() } })
        val inventory = port.inventorySources(_m["entry" j (("entry.py" j "READY = True\n").pair)])
        try {
            port.importInVm(inventory, "entry")
            assertTrue(port.vmStarted)
        } finally { port.close() }
        assertFalse(port.vmStarted)
    }

    @Test fun closeJoinsAnImportBeforeRetiringItsGuest() {
        val installing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val port = HermesPythonPort(install = {
            installing.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "import was not released" }
        })
        val inventory = port.inventorySources(_m["entry" j (("entry.py" j "READY = True\n").pair)])
        val importer = Thread {
            try { port.importInVm(inventory, "entry") }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        val closer = Thread {
            closing.countDown()
            try { port.close() }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { closed.countDown() }
        }
        try {
            importer.start()
            assertTrue(installing.await(30, TimeUnit.SECONDS), "import did not reach installation")
            closer.start()
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(closed.await(1, TimeUnit.SECONDS), "close returned before the admitted import")
            release.countDown()
            importer.join(30_000)
            closer.join(30_000)
            assertFalse(importer.isAlive, "import did not finish")
            assertFalse(closer.isAlive, "close did not finish")
            failure.get()?.let { throw it }
            assertFalse(port.vmStarted, "close left the imported guest alive")
            assertFailsWith<IllegalStateException> { port.evalInVm("True") }
        } finally {
            release.countDown()
            importer.join(30_000)
            if (closer.state != Thread.State.NEW) closer.join(30_000)
            port.close()
        }
    }
}
