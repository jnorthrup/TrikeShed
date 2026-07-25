package borg.trikeshed.utils.kanban

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.jules.WorkIdentity
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TimeseriesWalCursorTest {
    @Test
    fun replayReturnsOneWalRowPerCauseInATimeseriesCursor() = runBlocking {
        val forge = Files.createTempDirectory("ts-simple").toFile()
        val wal = JvmAppendWal(File(forge, "board.wal"))
        val store = JulesBoardStore(wal)
        val sid = "sess-001"
        val wid = "todo:001"
        store.appendWork(wid, JulesCause.WorkDispatched(
            workId = wid, sessionId = sid, attempt = 1, at = 1L,
        ))
        store.appendWork(wid, JulesCause.WorkIdentitySynthesized(
            workId = wid, identity = WorkIdentity(wid, sid), at = 2L,
        ))

        val cursor = TimeseriesWalCursor(store)
        val rows: WalCursor = cursor.replay()
        assertEquals(2, rows.size, "two cause records -> two WalRow")

        // stdlib-boundary: convert to a list to assert per-row content.
        // PRELOAD.md:99-101 permits this final materialization at the
        // assertion boundary (stdlib requires List for assertEquals pairs).
        val materialized: List<WalRow> = rows.view.toList()
        assertEquals(sid, materialized[0].identityKey)
        assertEquals("https://jules.google.com/session/$sid",
            materialized[0].synonyms.sessionUrl)
        assertEquals(null, materialized[0].synonyms.commitSha)
        assertEquals(null, materialized[1].synonyms.commitSha,
            "no drain -> commit still null on second row")
    }

    @Test
    fun replaySplitProducesSeriesOfJoinedKeyOps() = runBlocking {
        val forge = Files.createTempDirectory("ts-split2").toFile()
        val wal = JvmAppendWal(File(forge, "board.wal"))
        val store = JulesBoardStore(wal)
        store.appendWork("todo:split", JulesCause.WorkDispatched(
            workId = "todo:split", sessionId = "sess-s", attempt = 1, at = 1L,
        ))
        val cursor = TimeseriesWalCursor(store)
        val split: WalCursorSplit = cursor.replaySplit()
        assertEquals(1, split.size, "one joined row in the split")
        val first: WalCursorSplit = split
        assertNotNull(first[0].b.sessionUrl)
    }
}
