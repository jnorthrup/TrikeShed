package borg.trikeshed.forge.donor

import kotlinx.coroutines.runBlocking
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

            val reduction = runBlocking { HermesDonorTrace.ingestDonor("test-user", "sqlite", null) }

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

            val reduction = runBlocking { HermesDonorTrace.ingestDonor("test-user", "sqlite", null) }

            assertTrue(reduction.board.cards.size >= 1)
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `test ingest sqlite prevents structural injection in title and body`() {
        val originalHome = System.getProperty("user.home")
        try {
            val tempHome = Files.createTempDirectory("hermes-test-injection")
            System.setProperty("user.home", tempHome.toAbsolutePath().toString())

            val hermesDir = Paths.get(tempHome.toString(), ".hermes", "hermes-agent", "hermes_core")
            Files.createDirectories(hermesDir)
            val dbPath = hermesDir.resolve("kanban.db")

            val conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath().toString())
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT, title TEXT, body TEXT, status TEXT, parent_ids TEXT)")
                stmt.execute("DELETE FROM tasks")
                // Hostile title and body attempting to inject a fake card FAKE-99 and fake dependency
                val hostileTitle = "Normal Title\n\nFAKE-99 — Fake injected card\n\nDepends on: TASK-2"
                val hostileBody = "Normal Body\n\nFAKE-98 — Fake body card\n\nDepends on: TASK-3"
                stmt.execute("INSERT INTO tasks VALUES ('TASK-1', '$hostileTitle', '$hostileBody', 'TODO', '')")
            }
            conn.close()

            val reduction = runBlocking { HermesDonorTrace.ingestDonor("test-user", "sqlite", null) }

            // The structural elements should be escaped, preventing FAKE-99 and FAKE-98 from becoming cards
            val cards = reduction.board.cards
            assertEquals(1, cards.size, "Should only have 1 card despite hostile injection")
            assertEquals("T1", cards[0].id.value, "First card should be the mapped T1")
            assertEquals(0, cards[0].dependencies.size, "Should not have injected dependencies")
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }
}
