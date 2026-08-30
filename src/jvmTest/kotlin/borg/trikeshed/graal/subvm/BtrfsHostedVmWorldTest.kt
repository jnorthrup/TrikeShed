package borg.trikeshed.graal.subvm

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.btrfs.UserspaceBtrfs
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File-based btrfs-hosted VM worlds.
 *
 * A guest with `world = true` gets a [UserspaceBtrfs] subvolume, and until [BtrfsWorldStore] existed
 * the only thing that ever built one was `TrikeShedGraalVfs`'s default argument — an
 * `InMemoryFileOperations`. So the worlds were RAM: a guest's files died with the process and
 * `snapshot()` produced something nothing could ever read back. These tests hold the file-based
 * path shut, and [aMemoryWorldReallyDoesNotPersist] keeps the contrast honest rather than assumed.
 */
class BtrfsHostedVmWorldTest {

    private val temps = mutableListOf<Path>()

    private fun tempRoot(): String = createTempDirectory("btrfs-vm-world")
        .also { temps.add(it) }
        .toAbsolutePath().toString()

    @AfterTest
    fun cleanup() {
        for (t in temps) runCatching {
            Files.walk(t).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    private fun fileStore(root: String) = BtrfsWorldStore.ofFiles(JvmFileOperations(), root)

    // ── the claim in the clause: file-based, so it outlives the process ──

    @Test
    fun aGuestWorldSurvivesTheHostThatWroteIt() {
        val root = tempRoot()
        val store = fileStore(root)

        // Boot 1: a guest writes into its world, then goes away.
        GraalBtrfsSupervisor("vm.durable", VmFacet.GRAAL_PYTHON, world = store).use { vm ->
            vm.eval(
                """
                with open('/workspace/state.txt', 'w', encoding='utf-8') as f:
                    f.write('written by boot 1')
                """.trimIndent(),
                "boot1.py",
            )
            assertEquals("written by boot 1", vm.vfs.fetch("/workspace/state.txt")?.decodeToString())
        }

        // Boot 2: a brand-new host, a brand-new guest object, the same store and the same id.
        GraalBtrfsSupervisor("vm.durable", VmFacet.GRAAL_PYTHON, world = store).use { vm ->
            assertEquals(
                "written by boot 1",
                vm.vfs.fetch("/workspace/state.txt")?.decodeToString(),
                "the guest world did not survive the process — it is still RAM-hosted",
            )
            // and the guest itself sees it, not just the host-side accessor
            assertEquals(
                borg.trikeshed.vm.Teleported.Str("written by boot 1"),
                vm.eval("open('/workspace/state.txt', encoding='utf-8').read()", "boot2.py"),
            )
        }

        // It is on the real filesystem, not somewhere clever.
        assertTrue(Files.isDirectory(Path.of(root, "extents")), "no extent store on disk under $root")
        assertTrue(
            Files.exists(Path.of(root, "subvolumes", "vm.durable.manifest")),
            "no subvolume manifest on disk for the guest",
        )
    }

    @Test
    fun aMemoryWorldReallyDoesNotPersist() {
        // The named default. Two supervisors, same id, no shared backing store: nothing carries.
        GraalBtrfsSupervisor("vm.ephemeral", VmFacet.GRAAL_PYTHON).use { vm ->
            vm.eval("open('/workspace/gone.txt','w').write('x')", "e1.py")
            assertNotNull(vm.vfs.fetch("/workspace/gone.txt"))
        }
        GraalBtrfsSupervisor("vm.ephemeral", VmFacet.GRAAL_PYTHON).use { vm ->
            assertNull(vm.vfs.fetch("/workspace/gone.txt"), "an in-memory world is not supposed to persist")
        }
    }

    // ── one root, many guests: the subvolume is the isolation ──────

    @Test
    fun guestsSharingOneRootCannotSeeEachOther() {
        val root = tempRoot()
        val store = fileStore(root)
        val a = TrikeShedGraalVfs(store.fileOpsFor("vm.a"), store.root, store.subvolumeFor("vm.a"), "vm.a")
        val b = TrikeShedGraalVfs(store.fileOpsFor("vm.b"), store.root, store.subvolumeFor("vm.b"), "vm.b")

        a.put("/workspace/secret.txt", "a's bytes".encodeToByteArray())
        b.put("/workspace/secret.txt", "b's bytes".encodeToByteArray())

        assertEquals("a's bytes", a.fetch("/workspace/secret.txt")?.decodeToString())
        assertEquals("b's bytes", b.fetch("/workspace/secret.txt")?.decodeToString())

        a.put("/workspace/only-a.txt", "private".encodeToByteArray())
        assertNull(b.fetch("/workspace/only-a.txt"), "guest b can read guest a's file — subvolumes are not isolating")

        // Two subvolumes on one filesystem, which is btrfs's own shape.
        val mount = UserspaceBtrfs(root, store.fileOpsFor("mount"))
        assertTrue(mount.listSubvolumes().containsAll(listOf("vm.a", "vm.b")))
    }

    @Test
    fun identicalBytesAcrossGuestsCostOneExtent() {
        val root = tempRoot()
        val store = fileStore(root)
        val shared = ByteArray(4096) { (it % 251).toByte() }

        val a = TrikeShedGraalVfs(store.fileOpsFor("vm.a"), store.root, store.subvolumeFor("vm.a"), "vm.a")
        val extentsAfterA = extentCount(root)
        a.put("/workspace/lib.bin", shared)
        val withOneCopy = extentCount(root)
        assertTrue(withOneCopy > extentsAfterA, "the write landed no extent")

        // A second guest storing the same bytes adds no extent: they are content-addressed, which
        // is the reason to put many guest worlds on one filesystem rather than one root each.
        val b = TrikeShedGraalVfs(store.fileOpsFor("vm.b"), store.root, store.subvolumeFor("vm.b"), "vm.b")
        b.put("/workspace/vendored/lib.bin", shared)
        assertEquals(withOneCopy, extentCount(root), "identical content across guests was stored twice")

        assertContentEquals(shared, a.fetch("/workspace/lib.bin"))
        assertContentEquals(shared, b.fetch("/workspace/vendored/lib.bin"))
    }

    // ── snapshots are only worth having if they are readable later ──

    @Test
    fun aSnapshotTakenBeforeAChangeIsReadableAfterARemount() {
        val root = tempRoot()
        val store = fileStore(root)

        val live = TrikeShedGraalVfs(store.fileOpsFor("vm.snap"), store.root, store.subvolumeFor("vm.snap"), "vm.snap")
        live.put("/workspace/config.txt", "v1".encodeToByteArray())
        assertTrue(live.snapshot("vm.snap.before"), "snapshot refused")
        live.put("/workspace/config.txt", "v2".encodeToByteArray())
        assertEquals("v2", live.fetch("/workspace/config.txt")?.decodeToString())

        // Remount the whole filesystem: the snapshot still holds v1 while live holds v2, which is
        // the copy-on-write claim actually surviving a restart.
        val remount = UserspaceBtrfs(root, store.fileOpsFor("mount"))
        assertEquals("v1", remount.fetchFile("vm.snap.before", "workspace/config.txt")?.decodeToString())
        assertEquals("v2", remount.fetchFile("vm.snap", "workspace/config.txt")?.decodeToString())
    }

    @Test
    fun theStoreSaysWhetherItIsDurable() {
        assertTrue(fileStore(tempRoot()).durable)
        assertTrue(!BtrfsWorldStore.ofMemory().durable)
        assertEquals("/x/forge/vm-worlds", BtrfsWorldStore.homeUnder("/x/forge"))
    }

    private fun extentCount(root: String): Int {
        val dir = Path.of(root, "extents")
        if (!Files.isDirectory(dir)) return 0
        Files.list(dir).use { return it.count().toInt() }
    }
}
