package borg.trikeshed.graal.subvm

import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
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
}
