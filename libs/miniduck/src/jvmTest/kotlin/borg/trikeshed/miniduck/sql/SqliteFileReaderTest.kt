package borg.trikeshed.miniduck.sql

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * TDD: SQLite FileReader for Cursor
 * 
 * RED: Read SQLite files into Cursor (Series<RowVec>)
 */
class SqliteFileReaderTest {

    @Test
    fun `read sqlite file into cursor`() {
        // Given a SQLite file
        val file = java.io.File.createTempFile("test", ".db")
        file.deleteOnExit()
        
        // Create test table
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE tasks (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    status TEXT
                )
            """.trimIndent())
            conn.createStatement().execute("""
                INSERT INTO tasks (id, title, status) VALUES 
                ('t1', 'first task', 'todo'),
                ('t2', 'second task', 'done')
            """.trimIndent())
        }
        
        // When we read the file into a Cursor
        val cursor = SqliteFileReader.read(file.absolutePath, "tasks")
        
        // Then we get RowVec rows
        assertNotNull(cursor)
        assertEquals(2, cursor.size)
        
        val firstRow = cursor[0]
        assertEquals("t1", firstRow["id"])
        assertEquals("first task", firstRow["title"])
        assertEquals("todo", firstRow["status"])
    }

    @Test
    fun `query with filter returns matching rows`() {
        // Given a SQLite file with data
        val file = java.io.File.createTempFile("test", ".db")
        file.deleteOnExit()
        
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE tasks (id TEXT, title TEXT, status TEXT)
            """.trimIndent())
            conn.createStatement().execute("""
                INSERT INTO tasks VALUES 
                ('t1', 'task one', 'todo'),
                ('t2', 'task two', 'done')
            """.trimIndent())
        }
        
        // When we query with filter
        val cursor = SqliteFileReader.query(
            file.absolutePath,
            "tasks",
            "status = ?",
            listOf("done")
        )
        
        // Then we get only matching rows
        assertEquals(1, cursor.size)
        assertEquals("t2", cursor[0]["id"])
    }
}