package borg.trikeshed.hermes

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.lib.*
import borg.trikeshed.vm.Teleported
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.test.*

class HermesMemoryWorkspaceLeaseTest {
    @Test fun sharedMemoryWorldHasOnePortOwnerUntilClose() {
        val world = BtrfsWorldStore.ofMemory()
        val first = HermesPythonPort(world = world)
        var installed = false
        val second = HermesPythonPort(world = world, install = { installed = true })
        val separate = HermesPythonPort(world = BtrfsWorldStore.ofMemory())
        val source = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        try {
            first.importInVm(first.inventorySources(source), "entry")
            first.evalInVm("f = open('/workspace/shared.txt', 'w'); f.write('retained'); f.close(); True")
            separate.importInVm(separate.inventorySources(source), "entry")
            val refused = assertFailsWith<IllegalStateException> { second.importInVm(second.inventorySources(source), "entry") }
            assertContains(refused.message.orEmpty(), "memory workspace is already owned")
            assertFalse(installed, "competing port mounted the same memory workspace")
            assertEquals(Teleported.Str("retained"), first.evalInVm("open('/workspace/shared.txt').read()"))
            first.close()
            second.importInVm(second.inventorySources(source), "entry")
            assertTrue(installed)
            assertEquals(Teleported.Str("retained"), second.evalInVm("open('/workspace/shared.txt').read()"))
            assertFailsWith<IllegalStateException> { first.importInVm(first.inventorySources(source), "entry") }
        } finally {
            try { first.close() } finally { try { second.close() } finally { separate.close() } }
        }
    }

    @Test fun concurrentImportsClaimOneMemoryWorkspace() {
        val world = BtrfsWorldStore.ofMemory()
        val ports = _a[HermesPythonPort(world = world), HermesPythonPort(world = world)]
        val source = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val failures = AtomicReferenceArray<Throwable?>(2)
        val workers = Array(2) { i -> Thread {
            ready.countDown()
            try {
                check(start.await(30, TimeUnit.SECONDS))
                ports[i].importInVm(ports[i].inventorySources(source), "entry")
            } catch (failure: Throwable) { failures.set(i, failure) }
        }.apply { isDaemon = true } }
        try {
            workers.forEach { it.start() }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            start.countDown()
            workers.forEach { it.join(30_000) }
            assertTrue(workers.none { it.isAlive }, "concurrent imports did not finish")
            assertEquals(1, (0..1).count { failures.get(it) == null })
            val refused = assertIs<IllegalStateException>((0..1).mapNotNull { failures.get(it) }.single())
            assertContains(refused.message.orEmpty(), "memory workspace is already owned")
            assertEquals(1, ports.count { it.vmStarted })
        } finally {
            start.countDown()
            workers.forEach { if (it.state != Thread.State.NEW) it.join(30_000) }
            if (workers.none { it.isAlive }) ports.forEach { it.close() }
        }
    }
}
