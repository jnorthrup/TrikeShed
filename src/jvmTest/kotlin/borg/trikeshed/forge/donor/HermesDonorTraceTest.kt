package borg.trikeshed.forge.donor

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.nio.file.Files
import org.sqlite.SQLiteDataSource

class HermesDonorTraceTest {

    @Test
    fun testIngestMarkdown() {
        val tmpFile = Files.createTempFile("donor-test", ".md")
        val content = """
            TARGET: MD Donor Replay
            
            6. Work packages
            
            ### task1 — Task One
            
            This is task one body.
            
            7. 
        """.trimIndent()
        Files.writeString(tmpFile, content)
        
        val reduction = HermesDonorTrace.ingestDonor("user1", "md", tmpFile.toString())
        
        assertEquals(1, reduction.board.cards.size)
        assertEquals("task1", reduction.board.cards[0].id.value)
        assertTrue(reduction.board.cards[0].title.contains("Task One"))
    }
    
    @Test
    fun testIngestSqlite() {
        val tmpDb = Files.createTempFile("donor-test", ".db")
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${tmpDb.toAbsolutePath()}"
        }
        
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE tasks (id TEXT, title TEXT, body TEXT, status TEXT, parent_ids TEXT)")
                stmt.execute("INSERT INTO tasks VALUES ('task2', 'Task Two', 'Body Two', 'TODO', '')")
            }
        }
        
        val reduction = HermesDonorTrace.ingestDonor("user1", "sqlite", tmpDb.toString())
        
        assertEquals(1, reduction.board.cards.size)
        assertEquals("task2", reduction.board.cards[0].id.value)
        assertTrue(reduction.board.cards[0].title.contains("Task Two"))
    }
}
