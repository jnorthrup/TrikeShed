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

    // seedFile must reproduce the base exactly: later patches anchor to base CHARACTER offsets, so a
    // seed that re-splits lines (dropping CR, adding a phantom line) shifts every anchor after it.
    @Test
    fun unequalLinesAndFinalNewlineSeedExactly() {
        val base = "alpha\nbe\n\ngamma-long-line\n"
        val channel = PijulChannel()
        channel.seedFile("f.txt", base)
        assertEquals(base, channel.renderFile("f.txt"))
    }

    @Test
    fun baseWithoutFinalNewlineSeedsExactly() {
        val base = "alpha\nbeta"
        val channel = PijulChannel()
        channel.seedFile("f.txt", base)
        assertEquals(base, channel.renderFile("f.txt"))
    }

    @Test
    fun crlfBaseIsPreservedByteForByte() {
        val base = "a\r\nbb\r\nccc"
        val channel = PijulChannel()
        channel.seedFile("f.txt", base)
        assertEquals(base, channel.renderFile("f.txt"))
    }
}
