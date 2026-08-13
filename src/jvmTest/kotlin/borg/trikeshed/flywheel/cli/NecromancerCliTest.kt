package borg.trikeshed.flywheel.cli

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class NecromancerCliTest {
    @Test
    fun retiredWorkIsReportedButNeverRequeued() = runBlocking {
        val forgeDir = Files.createTempDirectory("necromancer-cli-test").toFile()
        try {
            val store = JulesBoardStore(JvmAppendWal(File(forgeDir, JulesBoardStore.WAL_FILENAME)))
            store.appendWork("retired-work", JulesCause.WorkQueued(
                workId = "retired-work",
                tier = "forge",
                title = "Already retired work",
                spec = "do not requeue",
                at = 1L,
            ))
            store.appendWork("retired-work", JulesCause.WorkDrained(
                workId = "retired-work",
                sessionId = "session-1",
                commitSha = "outbox-session-1",
                taskId = "retired",
                at = 2L,
            ))

            main(arrayOf(System.getProperty("user.dir"), forgeDir.absolutePath))

            assertEquals(listOf("retired-work"), store.loadQueue().map { it.workId })
        } finally {
            forgeDir.deleteRecursively()
        }
    }

    @Test
    fun legacyNecromanceEntryIsClosedWithoutPostingReplacementWork() = runBlocking {
        val forgeDir = Files.createTempDirectory("necromancer-legacy-test").toFile()
        try {
            val store = JulesBoardStore(JvmAppendWal(File(forgeDir, JulesBoardStore.WAL_FILENAME)))
            store.appendWork("gap:necromance:legacy:1", JulesCause.WorkQueued(
                workId = "gap:necromance:legacy:1",
                tier = "forge",
                title = "Duplicate legacy work",
                spec = "do not post",
                parent = "retired-work",
                at = 1L,
            ))

            main(arrayOf(System.getProperty("user.dir"), forgeDir.absolutePath))

            val entry = store.loadQueue().single()
            assertEquals("superseded", entry.taskId)
            assertEquals(true, entry.isDrained)
        } finally {
            forgeDir.deleteRecursively()
        }
    }
}
