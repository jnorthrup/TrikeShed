package borg.trikeshed.kanban

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.AfterTest

class JiraQueueAdapterTest {
    private val tempFile = Files.createTempFile("jira_wal", ".wal").toFile()

    @AfterTest
    fun cleanup() {
        tempFile.delete()
    }

    @Test
    fun `test ingest and sync`() = runBlocking {
        val wal = AppendWal(tempFile.absolutePath)
        val adapter = JiraQueueAdapter(wal)

        adapter.ingest("PROJ-123", "Fix crash", "App crashes on start")
        adapter.ingest("PROJ-124", "Add button", "Needs a submit button")

        wal.close()

        // reopen to test persistence
        val wal2 = AppendWal(tempFile.absolutePath)
        val adapter2 = JiraQueueAdapter(wal2)
        val syncData = adapter2.sync()

        assertEquals(2, syncData.size)
        assertTrue(syncData.containsKey("PROJ-123"))
        assertEquals("Fix crash\nApp crashes on start", syncData["PROJ-123"])

        assertTrue(syncData.containsKey("PROJ-124"))
        assertEquals("Add button\nNeeds a submit button", syncData["PROJ-124"])

        wal2.close()
    }
}
