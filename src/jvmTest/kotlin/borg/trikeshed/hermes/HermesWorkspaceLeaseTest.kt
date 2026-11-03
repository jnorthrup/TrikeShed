package borg.trikeshed.hermes

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileLease
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HermesWorkspaceLeaseTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    private fun probe(path: Path): String {
        val program = directory.resolve("LeaseProbe.java")
        Files.writeString(program, """
            import java.nio.channels.*;
            import java.nio.file.*;
            public class LeaseProbe {
                public static void main(String[] args) throws Exception {
                    try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                         FileLock lock = channel.tryLock()) {
                        System.out.print(lock == null ? "held" : "free");
                    }
                }
            }
        """.trimIndent())
        val java = Path.of(java.lang.System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(java, program.toString(), path.toString()).redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "lease probe did not finish")
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(0, child.exitValue(), output)
            return output
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                child.waitFor(20, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun durablePortsCannotMountTheSameWorldTogether() {
        val root = directory.resolve("world")
        val first = HermesPythonPort(world = BtrfsWorldStore.ofFiles(JvmFileOperations(), root.toString()))
        var secondInstalled = false
        val second = HermesPythonPort(world = BtrfsWorldStore.ofFiles(JvmFileOperations(), root.toString()),
            install = { secondInstalled = true })
        val sources = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        try {
            first.importInVm(first.inventorySources(sources), "entry")
            assertFails { second.importInVm(second.inventorySources(sources), "entry") }
            assertFalse(secondInstalled, "competing port mounted the writable world")
            first.close()
            second.importInVm(second.inventorySources(sources), "entry")
            assertTrue(secondInstalled, "closed owner did not release the writable world")
        } finally {
            try { first.close() } finally { second.close() }
        }
    }

    @Test fun durableOwnerIsVisibleToAnotherProcessAndDifferentWorldsRemainIndependent() {
        val root = directory.resolve("world")
        val otherRoot = directory.resolve("other")
        Files.createDirectories(root.resolve("owners"))
        val alias = directory.resolve("alias")
        val sameRoot = runCatching { Files.createSymbolicLink(alias, root) }.getOrElse { root }
        val first = HermesPythonPort(world = BtrfsWorldStore.ofFiles(JvmFileOperations(), root.toString()))
        val same = HermesPythonPort(world = BtrfsWorldStore.ofFiles(JvmFileOperations(), sameRoot.toString()))
        val other = HermesPythonPort(world = BtrfsWorldStore.ofFiles(JvmFileOperations(), otherRoot.toString()))
        val sources = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        val lock = root.resolve("owners/hermes-python.lock")
        try {
            first.importInVm(first.inventorySources(sources), "entry")
            other.importInVm(other.inventorySources(sources), "entry")
            assertFails { same.importInVm(same.inventorySources(sources), "entry") }
            assertEquals("held", probe(lock))
            first.close()
            assertEquals("free", probe(lock))
        } finally {
            try { first.close() } finally { try { same.close() } finally { other.close() } }
        }
    }

    @Test fun hardLinkContentionDoesNotReleaseAnotherProcessLock() {
        val path = directory.resolve("first.lock")
        val alias = directory.resolve("alias.lock")
        Files.createFile(path)
        Files.createLink(alias, path)
        val first = JvmFileOperations().acquireExclusiveLease(path.toString())
        try {
            assertFails { JvmFileOperations().acquireExclusiveLease(alias.toString()) }
            assertEquals("held", probe(alias))
            Files.delete(path)
            assertFails { JvmFileOperations().acquireExclusiveLease(alias.toString()) }
            assertEquals("held", probe(alias))
        } finally { first.close() }
        assertEquals("free", probe(alias))
    }

    @Test fun concurrentCaseVariantsRespectFilesystemIdentity() {
        val upper = directory.resolve("Case.lock")
        val lower = directory.resolve("case.lock")
        Files.createFile(upper)
        val insensitive = Files.exists(lower)
        Files.delete(upper)
        repeat(8) { attempt ->
            val ready = CountDownLatch(2)
            val gate = CountDownLatch(1)
            val done = CountDownLatch(2)
            val acquired = arrayOfNulls<FileLease>(2)
            val paths = _a[upper, lower]
            val threads = Array(2) { index -> Thread {
                ready.countDown()
                try {
                    check(gate.await(20, TimeUnit.SECONDS))
                    acquired[index] = runCatching { JvmFileOperations().acquireExclusiveLease(paths[index].toString()) }.getOrNull()
                } finally { done.countDown() }
            }.apply { isDaemon = true; start() } }
            try {
                assertTrue(ready.await(20, TimeUnit.SECONDS))
                gate.countDown()
                assertTrue(done.await(20, TimeUnit.SECONDS))
                assertEquals(if (insensitive) 1 else 2, acquired.count { it != null })
                if (attempt == 0) assertEquals("held", probe(paths[acquired.indexOfFirst { it != null }]))
            } finally {
                gate.countDown()
                threads.forEach { it.join(20_000) }
                acquired.forEach { it?.close() }
                Files.deleteIfExists(upper)
                Files.deleteIfExists(lower)
            }
        }
    }

    @Test fun failedWorkspaceLeaseCloseKeepsTheWorldOwnedUntilRetry() {
        val root = directory.resolve("world")
        val base = JvmFileOperations()
        var closes = 0
        val failing = object : FileOperations by base {
            override fun acquireExclusiveLease(path: String): FileLease {
                val lease = base.acquireExclusiveLease(path)
                return object : FileLease {
                    override fun close() {
                        if (++closes == 1) error("injected lease close failure")
                        lease.close()
                    }
                }
            }
        }
        val first = HermesPythonPort(world = BtrfsWorldStore.ofFiles(failing, root.toString()))
        val second = HermesPythonPort(world = BtrfsWorldStore.ofFiles(JvmFileOperations(), root.toString()))
        val sources = _m["entry" j (("entry.py" j "READY = True\n").pair)]
        try {
            first.importInVm(first.inventorySources(sources), "entry")
            assertFailsWith<IllegalStateException> { first.close() }
            assertFails { second.importInVm(second.inventorySources(sources), "entry") }
            first.close()
            second.importInVm(second.inventorySources(sources), "entry")
            assertEquals(2, closes)
        } finally {
            try { first.close() } finally { second.close() }
        }
    }
}
