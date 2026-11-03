package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.*
import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.hermes.HermesPythonPort
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.vm.Teleported
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class PythonFileLeasesPublicationTest {
    @Test fun realGraalSleevePublishesOnlyItsPairedTarget() {
        val directory = Files.createTempDirectory("hermes-vfs-guest-")
        val world = BtrfsWorldStore.ofFiles(JvmFileOperations(), directory.toString())
        try {
            HermesPythonPort(world = world).use { port ->
                val sleeve = Files.readString(Path.of("utils/graalpy-sleeve/python/trikeshed_vfs.py"))
                val inventory = port.inventorySources(
                    _m["entry" j (("entry.py" j "import trikeshed_vfs\nREADY = True\n").pair)],
                    sleeveSources = _m["trikeshed_vfs" j (("trikeshed_vfs.py" j sleeve).pair)],
                )
                port.importInVm(inventory, "entry")
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    from trikeshed_vfs import ExclusiveLease, publish_json
                    lease = ExclusiveLease('/workspace/registry.lock', publication='/workspace/registry.json').acquire()
                    publish_json('/workspace/registry.json', {'ok': True})
                    True
                """.trimIndent(), "lease-publication.py"))
                assertFailsWith<GuestException> {
                    port.evalInVm("publish_json('/workspace/unrelated.json', {'bad': True})", "lease-refusal.py")
                }
                assertEquals(Teleported.Bool(true), port.evalInVm("lease.release()\nTrue", "lease-release.py"))
            }
            val committed = world.mount("hermes-python").fetchFile("hermes-python", "workspace/registry.json")
            assertEquals("{\"ok\": true}", committed?.decodeToString())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun publicationRequiresItsOwnExclusiveTarget() {
        val directory = Files.createTempDirectory("hermes-vfs-leases-")
        val published = mutableMapOf<String, String>()
        val first = PythonFileLeases(JvmFileOperations(), directory.toString(), { path, bytes -> published[path] = bytes.decodeToString() })
        val second = PythonFileLeases(JvmFileOperations(), directory.toString(), { path, bytes -> published[path] = bytes.decodeToString() })
        fun call(owner: PythonFileLeases, vararg values: String) = owner.call(values.map { Teleported.Str(it) })
        fun acquire(owner: PythonFileLeases, lock: String, target: String) =
            (call(owner, "acquire", lock, target) as Teleported.Str).v
        try {
            val a = acquire(first, "/workspace/a.lock", "/workspace/a.json")
            assertFailsWith<IllegalStateException> { call(first, "publish", "/workspace/b.json", "wrong") }
            assertEquals(Teleported.Bool(true), call(first, "publish", "/workspace/a.json", "first"))
            // Another lock name cannot grant concurrent access to the same target.
            assertFails { acquire(second, "/workspace/b.lock", "/workspace/a.json") }
            // The original lock name remains exclusive for older peers.
            assertFails { acquire(second, "/workspace/a.lock", "/workspace/b.json") }
            val b = acquire(second, "/workspace/b.lock", "/workspace/b.json")
            call(first, "release", a)
            assertFailsWith<IllegalStateException> { call(first, "publish", "/workspace/a.json", "released") }
            assertFailsWith<IllegalStateException> { call(second, "publish", "/workspace/a.json", "wrong") }
            call(second, "release", b)
            val next = acquire(second, "/workspace/b.lock", "/workspace/a.json")
            call(second, "publish", "/workspace/a.json", "second")
            call(second, "release", next)
            assertEquals(_m["/workspace/a.json" j "second"], published)
        } finally {
            first.close(); second.close()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun failedPartialAcquisitionRetainsAnUnreleasedLeaseForOwnerCleanup() {
        val memory = InMemoryFileOperations()
        var acquired = 0
        var closes = 0
        val files = object : FileOperations by memory {
            override fun acquireExclusiveLease(path: String): FileLease {
                if (++acquired == 2) error("second lease unavailable")
                return object : FileLease {
                    override fun close() { if (++closes == 1) error("first close failed") }
                }
            }
        }
        val owner = PythonFileLeases(files, "/leases", { _, _ -> })
        try {
            val failure = assertFailsWith<IllegalStateException> {
                owner.call(_l[Teleported.Str("acquire"), Teleported.Str("/a"), Teleported.Str("/b")])
            }
            assertEquals("first close failed", failure.suppressed.single().message)
            assertEquals(1, owner.leases.size)
            assertFailsWith<IllegalStateException> {
                owner.call(_l[Teleported.Str("publish"), Teleported.Str("/a"), Teleported.Str("bad")])
            }
        } finally { owner.close() }
        assertEquals(2, closes)
        assertTrue(owner.leases.isEmpty())
    }
}
