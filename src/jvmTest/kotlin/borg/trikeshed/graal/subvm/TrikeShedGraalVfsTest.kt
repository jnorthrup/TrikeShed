package borg.trikeshed.graal.subvm

import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrikeShedGraalVfsTest {
    @Test
    fun graalPythonCwdAndFileIoStayInsideUserspaceBtrfs() {
        GraalBtrfsSupervisor("vfs-python", VmFacet.GRAAL_PYTHON).use { supervisor ->
            val result = supervisor.eval(
                """
                import os
                with open('/workspace/state.txt', 'w', encoding='utf-8') as f:
                    f.write('blackboard')
                open('/workspace/state.txt', encoding='utf-8').read() + '|' + os.getcwd()
                """.trimIndent(),
                "vfs-smoke.py",
            )
            assertEquals(Teleported.Str("blackboard|/workspace"), result)
            assertEquals("blackboard", supervisor.vfs.fetch("/workspace/state.txt")?.decodeToString())
        }
    }

    @Test
    fun hostFilesystemIsNotVisibleThroughVirtualRoot() {
        GraalBtrfsSupervisor("vfs-deny-host", VmFacet.GRAAL_PYTHON).use { supervisor ->
            val failure = assertFailsWith<GuestException> {
                supervisor.eval("open('/etc/passwd').read()", "deny-host.py")
            }
            assertEquals(GuestFailure.GUEST_ERROR, failure.kind)
            assertTrue(failure.message.orEmpty().contains("No such file") || failure.message.orEmpty().contains("No such file or directory"))
        }
    }

    @Test
    fun snapshotIsImmutableAfterLiveMutation() {
        val vfs = TrikeShedGraalVfs()
        vfs.put("/workspace/module.py", "VALUE = 1".encodeToByteArray())
        assertTrue(vfs.snapshot("baseline"))
        vfs.put("/workspace/module.py", "VALUE = 2".encodeToByteArray())
        assertEquals("VALUE = 1", vfs.fetchSnapshot("baseline", "/workspace/module.py")?.decodeToString())
        assertEquals("VALUE = 2", vfs.fetch("/workspace/module.py")?.decodeToString())
    }

    /**
     * The guest-write bug: bytes were committed only on channel close, and GraalPy has no refcounted
     * close — an un-close()d writer silently lost everything that reached the channel. Every
     * mutation must commit, and O_CREAT/O_TRUNC must be visible at open(2), not at close.
     */
    @Test
    fun channelWritesAreDurableWithoutClose() {
        val vfs = TrikeShedGraalVfs()
        vfs.put("/workspace/seeded.txt", "stale".encodeToByteArray())
        val overwrite = vfs.newByteChannel(
            vfs.parsePath("/workspace/seeded.txt"),
            setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
        )
        assertEquals("", vfs.fetch("/workspace/seeded.txt")?.decodeToString(), "O_TRUNC empties at open")
        overwrite.write(ByteBuffer.wrap("fresh".encodeToByteArray()))
        assertEquals("fresh", vfs.fetch("/workspace/seeded.txt")?.decodeToString(), "unclosed write must persist")
        val create = vfs.newByteChannel(
            vfs.parsePath("/workspace/born.txt"),
            setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE),
        )
        assertEquals("", vfs.fetch("/workspace/born.txt")?.decodeToString(), "O_CREAT materializes at open")
        create.write(ByteBuffer.wrap("alive".encodeToByteArray()))
        create.truncate(1L)
        assertEquals("a", vfs.fetch("/workspace/born.txt")?.decodeToString(), "ftruncate is immediately visible")
        overwrite.close(); create.close()
        assertEquals("fresh", vfs.fetch("/workspace/seeded.txt")?.decodeToString(), "close never resurrects old bytes")
    }

    /** The pytest-world repro: a guest flush without close must persist (no refcounted close in GraalPy). */
    @Test
    fun graalPythonFlushWithoutCloseReachesTheStore() {
        GraalBtrfsSupervisor("vfs-guest-write", VmFacet.GRAAL_PYTHON).use { supervisor ->
            supervisor.put("/workspace/seeded.txt", "stale".encodeToByteArray())
            val result = supervisor.eval(
                """
                f = open('/workspace/seeded.txt', 'w', encoding='utf-8')
                f.write('fresh')
                f.flush()
                g = open('/workspace/born.txt', 'w', encoding='utf-8')
                g.write('alive')
                g.flush()
                open('/workspace/seeded.txt', encoding='utf-8').read()
                """.trimIndent(),
                "guest-write.py",
            )
            assertEquals(Teleported.Str("fresh"), result, "guest re-read must see the flushed content")
            assertEquals("fresh", supervisor.vfs.fetch("/workspace/seeded.txt")?.decodeToString())
            assertEquals("alive", supervisor.vfs.fetch("/workspace/born.txt")?.decodeToString())
        }
    }
}
