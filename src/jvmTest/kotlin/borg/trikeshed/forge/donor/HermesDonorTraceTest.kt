package borg.trikeshed.forge.donor

import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HermesDonorTraceTest {

    @Test
    fun `test ingest sqlite hermes agent db defaults to home hermes path`() {
        val originalHome = System.getProperty("user.home")
        try {
            val tempHome = Files.createTempDirectory("hermes-test-home")
            System.setProperty("user.home", tempHome.toAbsolutePath().toString())
            
            val hermesDir = Paths.get(tempHome.toString(), ".hermes", "hermes-agent", "hermes_core")
            Files.createDirectories(hermesDir)
            val dbPath = hermesDir.resolve("kanban.db")
            
            // Setup dummy db
            val conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath().toString())
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT, title TEXT, body TEXT, status TEXT, parent_ids TEXT)")
                stmt.execute("DELETE FROM tasks")
                stmt.execute("INSERT INTO tasks VALUES ('TASK-1', 'Test title', 'Test body', 'TODO', 'TASK-2')")
                stmt.execute("INSERT INTO tasks VALUES ('TASK-2', 'Test title 2', 'Test body 2', 'TODO', '')")
            }
            conn.close()
    
            val reduction = HermesDonorTrace.ingestDonor("test-user", "sqlite", null)
            
            assertTrue(reduction.board.cards.size >= 1)
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `test ingest sqlite hermes agent db defaults to old hermes path`() {
        val originalHome = System.getProperty("user.home")
        try {
            val tempHome = Files.createTempDirectory("hermes-test-home-old")
            System.setProperty("user.home", tempHome.toAbsolutePath().toString())
            
            val hermesDir = Paths.get(tempHome.toString(), ".hermes")
            Files.createDirectories(hermesDir)
            val dbPath = hermesDir.resolve("kanban.db")
    
            // Setup dummy db in OLD path
            val conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath().toString())
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT, title TEXT, body TEXT, status TEXT, parent_ids TEXT)")
                stmt.execute("DELETE FROM tasks")
                stmt.execute("INSERT INTO tasks VALUES ('TASK-1', 'Test title', 'Test body', 'TODO', 'TASK-2')")
                stmt.execute("INSERT INTO tasks VALUES ('TASK-2', 'Test title 2', 'Test body 2', 'TODO', '')")
            }
            conn.close()
    
            val reduction = HermesDonorTrace.ingestDonor("test-user", "sqlite", null)
            
            assertTrue(reduction.board.cards.size >= 1)
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }
}
