package borg.trikeshed.jules

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class FlywheelDriverTodoCacheTest {

    private lateinit var tempDir: File
    private lateinit var todoFile: File

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("flywheel-test").toFile()
        val docDir = File(tempDir, "doc")
        docDir.mkdirs()
        todoFile = File(docDir, "todo.md")
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `test todo cache correctly invalidates based on mtime`() {
        // Create 100 items
        val content = StringBuilder()
        for (i in 1..100) {
            content.append("- [ ] **Task $i**\n")
            content.append("    Spec for task $i\n")
            content.append("\n")
        }
        todoFile.writeText(content.toString())

        val driver = FlywheelDriver("test-api-key", repoDir = tempDir)

        // Use reflection to access private readTodoItems
        val readMethod = FlywheelDriver::class.java.getDeclaredMethod("readTodoItems")
        readMethod.isAccessible = true

        // Initial read
        val startInitial = System.nanoTime()
        val initialItems = readMethod.invoke(driver) as List<*>
        val timeInitial = System.nanoTime() - startInitial

        println("INITIAL ITEMS SIZE: ${initialItems.size}, items: $initialItems")
        val size = initialItems.size
        assertEquals(100, size, "Initial parse should yield 100 items but got $size. Contents: $initialItems")

        // Read 100 times without modification
        var totalCachedTime = 0L
        for (i in 1..100) {
            val startCached = System.nanoTime()
            val cachedItems = readMethod.invoke(driver) as List<*>
            totalCachedTime += System.nanoTime() - startCached

            assertEquals(100, cachedItems.size)
            assertTrue(initialItems === cachedItems, "List instances should be identical from cache")
        }

        // Now modify the file
        todoFile.writeText("- [ ] **Task 101**\n    Spec for task 101\n")
        // Ensure mtime is distinct enough (file systems might have varying resolution)
        todoFile.setLastModified(todoFile.lastModified() + 2000)

        // Fetch again, should have 1 item
        val updatedItems = readMethod.invoke(driver) as List<*>
        assertEquals(1, updatedItems.size)
        assertTrue(initialItems !== updatedItems, "List instance should be new after cache miss")
    }
}
