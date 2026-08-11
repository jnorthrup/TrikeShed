package borg.trikeshed.pijul

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertEquals

class PijulChannelTest {
    @Test
    fun `applied patch is retained and materialized through file operations`() {
        val channel = PijulChannel()
        val fileOps = InMemoryFileOperations()
        val patchCid = ContentId.of("patch".encodeToByteArray())

        val touched = channel.applyPatch(
            workId = "work-1",
            sessionId = "session-1",
            patchCid = patchCid,
            title = "add greeting",
            changes = listOf(
                FileChanges(
                    path = "src/greeting.txt",
                    inserts = listOf(0 j "hello"),
                ),
            ),
        )

        assertEquals(listOf("src/greeting.txt"), touched)
        assertEquals(1, channel.appliedPatches().size)
        assertEquals(listOf("src/greeting.txt"), channel.materialize("/integration", fileOps))
        assertEquals("hello", fileOps.readString("/integration/src/greeting.txt"))
    }

    @Test
    fun `reset clears the applied patch history`() {
        val channel = PijulChannel()

        channel.applyPatch(
            workId = "work-1",
            sessionId = "session-1",
            patchCid = ContentId.of("patch".encodeToByteArray()),
            title = "add greeting",
            changes = listOf(FileChanges("src/greeting.txt", inserts = listOf(0 j "hello"))),
        )
        channel.reset()

        assertEquals(0, channel.appliedPatches().size)
    }
}
