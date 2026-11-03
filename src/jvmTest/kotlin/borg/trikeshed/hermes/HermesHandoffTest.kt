package borg.trikeshed.hermes

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.GuestException
import borg.trikeshed.graal.subvm.GuestFailure
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.vm.Teleported
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class HermesHandoffTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test fun failedCandidateDoesNotPublishNewBaseline() {
        val world = BtrfsWorldStore.ofMemory()
        val port = HermesPythonPort(world = world)
        val old = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        val failing = _m["entry" j (("entry.py" j "raise RuntimeError('candidate failed')\n").pair)]
        try {
            port.importInVm(port.inventorySources(old), "entry")
            val before = world.mount("hermes-python").listSubvolumes()
            assertFails { port.importInVm(port.inventorySources(failing), "entry") }
            assertEquals(before, world.mount("hermes-python").listSubvolumes())
        } finally { port.close() }
    }

    @Test fun failedCandidateDoesNotDeletePreexistingBaseline() {
        val world = BtrfsWorldStore.ofMemory()
        var observed: borg.trikeshed.job.ContentId? = null
        val port = HermesPythonPort(world = world, install = { guest ->
            observed = guest.vfs.btrfsForTools().manifestId(guest.vfs.liveSubvolumeForTools())
        })
        val old = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        val source = "raise RuntimeError('candidate failed')\n"
        val failing = _m["entry" j (("entry.py" j source).pair)]
        try {
            port.importInVm(port.inventorySources(old), "entry")
            val inventory = port.inventorySources(failing)
            val seeded = world.mount("hermes-python")
            check(seeded.fork("hermes-python", "test-staging"))
            check(seeded.writeFile("test-staging", "workspace/entry.py", source.encodeToByteArray()))
            val cid = assertNotNull(seeded.manifestId("test-staging"))
            val baseline = "boot-${inventory.ontology.cid.hex.take(16)}-${cid.hex.take(16)}"
            check(seeded.snapshot("test-staging", baseline))
            check(seeded.deleteSubvolume("test-staging"))
            val before = world.mount("hermes-python").listSubvolumes()
            assertTrue(baseline in before)
            observed = null
            assertFails { port.importInVm(inventory, "entry") }
            assertEquals(cid, observed, "preseeded snapshot must match the candidate's pre-import tree")
            assertEquals(before, world.mount("hermes-python").listSubvolumes())
            assertContentEquals(source.encodeToByteArray(), world.mount("hermes-python").fetchFile(baseline, "workspace/entry.py"))
        } finally { port.close() }
    }

    @Test fun ambiguousBaselinePublicationRetiresCandidateAndKeepsCommittedWorkspace() {
        val delegate = JvmFileOperations()
        var fail = false
        val files = object : FileOperations by delegate {
            override fun writeAtomically(filename: String, bytes: ByteArray) {
                delegate.writeAtomically(filename, bytes)
                if (fail && filename.substringAfterLast('/').startsWith("boot-") && filename.endsWith(".manifest")) {
                    fail = false
                    error("failure after baseline rename")
                }
            }
        }
        val world = BtrfsWorldStore.ofFiles(files, directory.resolve("baseline-ambiguous").toString())
        val port = HermesPythonPort(world = world)
        val old = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        val next = _m["entry" j (("entry.py" j "with open('/workspace/result.txt', 'w') as f:\n    f.write('candidate')\n").pair)]
        try {
            port.importInVm(port.inventorySources(old), "entry")
            fail = true
            val failure = assertFails { port.importInVm(port.inventorySources(next), "entry") }
            assertEquals("failure after baseline rename", failure.message)
            assertFalse(port.vmStarted)
            assertTrue(world.mount("hermes-python").listSubvolumes().none { it.startsWith("hermes-python-") })
            port.close()
            val reopened = HermesPythonPort(world = world)
            try {
                reopened.importInVm(reopened.inventorySources(old), "entry")
                assertEquals(Teleported.Str("candidate"), reopened.evalInVm("open('/workspace/result.txt').read()"))
            } finally { reopened.close() }
        } finally { port.close() }
    }

    @Test fun failedVfsNotificationKeepsCommittedCandidateUsable() {
        val board = ConfixBlackboard.empty()
        val port = HermesPythonPort(blackboard = board)
        val source = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        var unsubscribe: (() -> Unit)? = null
        try {
            port.importInVm(port.inventorySources(source), "entry")
            val prior = assertNotNull(board.snapshot().provenance["hermes/python/vfs"]).revision
            var failed = false
            unsubscribe = board.subscribe {
                if (!failed && (board.snapshot().provenance["hermes/python/vfs"]?.revision ?: 0L) > prior) {
                    failed = true
                    assertTrue(port.vmStarted)
                    assertEquals(Teleported.Bool(true), port.evalInVm("True"))
                    error("VFS subscriber failed")
                }
            }
            val failure = assertFails { port.importInVm(port.inventorySources(source), "entry") }
            assertEquals("VFS subscriber failed", failure.message)
            assertTrue(failed)
            assertTrue(port.vmStarted, "the committed candidate must remain published")
            assertEquals(Teleported.Bool(true), port.evalInVm("True"))
            val pointcut = board.snapshot().values["hermes/python/pointcut/import/entry"] as? Map<*, *>
            assertEquals("loaded", pointcut?.get("status"))
        } finally {
            unsubscribe?.invoke()
            port.close()
        }
    }

    @Test fun failedManifestReadAfterForkDiscardsCandidateBranch() {
        val delegate = JvmFileOperations()
        var arm = false
        var failRead = false
        val files = object : FileOperations by delegate {
            override fun writeAtomically(filename: String, bytes: ByteArray) {
                delegate.writeAtomically(filename, bytes)
                if (arm && filename.substringAfterLast('/').startsWith("hermes-python-") && filename.endsWith(".manifest")) {
                    arm = false
                    failRead = true
                }
            }

            override fun readAllBytes(filename: String): ByteArray {
                if (failRead && filename.endsWith("/hermes-python.manifest")) {
                    failRead = false
                    error("canonical read failed after fork")
                }
                return delegate.readAllBytes(filename)
            }
        }
        val world = BtrfsWorldStore.ofFiles(files, directory.resolve("fork-failure").toString())
        val port = HermesPythonPort(world = world)
        val source = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        try {
            port.importInVm(port.inventorySources(source), "entry")
            val before = world.mount("hermes-python").listSubvolumes()
            arm = true
            val failure = assertFails { port.importInVm(port.inventorySources(source), "entry") }
            assertEquals("canonical read failed after fork", failure.message)
            assertEquals(Teleported.Bool(true), port.evalInVm("True"))
            assertEquals(before, world.mount("hermes-python").listSubvolumes())
        } finally { port.close() }
    }

    @Test fun failedCandidateDoesNotPublishItsWrites() {
        val world = BtrfsWorldStore.ofMemory()
        val first = HermesPythonPort(world = world)
        val ready = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        val failing = _m["entry" j (("entry.py" j "with open('/workspace/data.txt', 'w') as f:\n    f.write('candidate')\nraise RuntimeError('candidate failed')\n").pair)]
        try {
            first.importInVm(first.inventorySources(ready), "entry")
            first.evalInVm("with open('/workspace/data.txt', 'w') as f:\n    f.write('incumbent')\nTrue")
            assertFails { first.importInVm(first.inventorySources(failing), "entry") }
            assertEquals(Teleported.Str("incumbent"), first.evalInVm("open('/workspace/data.txt').read()"))
            first.close()
            val reopened = HermesPythonPort(world = world)
            try {
                reopened.importInVm(reopened.inventorySources(ready), "entry")
                assertEquals(Teleported.Str("incumbent"), reopened.evalInVm("open('/workspace/data.txt').read()"))
            } finally { reopened.close() }
        } finally { first.close() }
    }

    @Test fun successfulCandidateAndPriorDataSurviveReopen() {
        val root = directory.resolve("world").toString()
        fun port() = HermesPythonPort(world = BtrfsWorldStore.ofFiles(JvmFileOperations(), root))
        val first = port()
        val old = _m["entry" j (("entry.py" j "VERSION = 'old'\n").pair)]
        val next = _m["entry" j (("entry.py" j "VERSION = 'new'\nwith open('/workspace/new.txt', 'w') as f:\n    f.write('candidate')\n").pair)]
        try {
            first.importInVm(first.inventorySources(old), "entry")
            first.evalInVm("with open('/workspace/data.txt', 'w') as f:\n    f.write('incumbent')\nTrue")
            first.importInVm(first.inventorySources(next), "entry")
            assertEquals(Teleported.Str("new"), first.evalInVm("__import__('entry').VERSION"))
            first.close()
            val reopened = port()
            try {
                reopened.importInVm(reopened.inventorySources(next), "entry")
                assertEquals(Teleported.Str("incumbent"), reopened.evalInVm("open('/workspace/data.txt').read()"))
                assertEquals(Teleported.Str("candidate"), reopened.evalInVm("open('/workspace/new.txt').read()"))
            } finally { reopened.close() }
        } finally { first.close() }
    }

    @Test fun concurrentCrossingCannotWriteIntoRetiredWorkspace() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val installs = AtomicInteger()
        val port = HermesPythonPort(install = {
            if (installs.incrementAndGet() == 2) {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
        })
        val source = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        val failure = AtomicReference<Throwable?>()
        val writer = AtomicReference<Throwable?>()
        var replacement: Thread? = null
        var crossing: Thread? = null
        try {
            port.importInVm(port.inventorySources(source), "entry")
            port.evalInVm("with open('/workspace/data.txt', 'w') as f:\n    f.write('before')\nTrue")
            replacement = Thread { try { port.importInVm(port.inventorySources(source), "entry") } catch (t: Throwable) { failure.set(t) } }
            replacement.start()
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            crossing = Thread { try {
                port.evalInVm("with open('/workspace/data.txt', 'w') as f:\n    f.write('crossing')\nTrue")
            } catch (t: Throwable) { writer.set(t) } }
            crossing.start()
            release.countDown()
            replacement.join(30_000)
            crossing.join(30_000)
            assertFalse(replacement.isAlive)
            assertFalse(crossing.isAlive)
            failure.get()?.let { throw it }
            val writerFailure = writer.get()
            when (writerFailure) {
                null -> Unit
                is GuestException -> assertEquals(GuestFailure.DEAD, writerFailure.kind)
                is IllegalStateException -> assertEquals("Hermes VM has not been imported", writerFailure.message)
                else -> throw writerFailure
            }
            val expected = if (writerFailure == null) "crossing" else "before"
            assertEquals(Teleported.Str(expected), port.evalInVm("open('/workspace/data.txt').read()"))
        } finally {
            release.countDown()
            replacement?.join(30_000)
            crossing?.join(30_000)
            if (replacement?.isAlive != true && crossing?.isAlive != true) port.close()
        }
    }

    @Test fun ambiguousCanonicalPublicationRetiresBothMounts() {
        val delegate = JvmFileOperations()
        var fail = false
        val files = object : FileOperations by delegate {
            override fun writeAtomically(filename: String, bytes: ByteArray) {
                delegate.writeAtomically(filename, bytes)
                if (fail && filename.endsWith("/hermes-python.manifest")) {
                    fail = false
                    error("failure after canonical rename")
                }
            }
        }
        val root = directory.resolve("ambiguous").toString()
        val world = BtrfsWorldStore.ofFiles(files, root)
        val first = HermesPythonPort(world = world)
        val old = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        val next = _m["entry" j (("entry.py" j "with open('/workspace/result.txt', 'w') as f:\n    f.write('candidate')\n").pair)]
        try {
            first.importInVm(first.inventorySources(old), "entry")
            fail = true
            assertFails { first.importInVm(first.inventorySources(next), "entry") }
            assertFalse(first.vmStarted)
            first.close()
            val reopened = HermesPythonPort(world = world)
            try {
                reopened.importInVm(reopened.inventorySources(old), "entry")
                assertEquals(Teleported.Str("candidate"), reopened.evalInVm("open('/workspace/result.txt').read()"))
            } finally { reopened.close() }
        } finally { first.close() }
    }
}
