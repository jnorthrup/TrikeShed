package borg.trikeshed.jules

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class FlywheelDriverSpecGuardTest {
    private val repoDir = File("build/tmp/test-repo")

    @BeforeTest
    fun setup() {
        repoDir.mkdirs()
        File(repoDir, ".git").mkdirs()

        val dummyGit = File(repoDir, "git")
        dummyGit.writeText("#!/bin/sh\nexit 0")
        dummyGit.setExecutable(true)
    }

    @AfterTest
    fun teardown() {
        repoDir.deleteRecursively()
    }

    @Test
    fun rejectsEmptySpecTodoItemsAndEmitsEvent() = runTest {
        val docDir = File(repoDir, "doc").apply { mkdirs() }
        val todoMd = File(docDir, "todo.md")
        todoMd.writeText("""
            - [ ] **Item 1**
              Body for 1
            - [ ] **Item 2**
            - [ ] **Item 3**
              Body for 3
        """.trimIndent())

        ProcessBuilder("git", "init").directory(repoDir).start().waitFor()
        ProcessBuilder("git", "config", "user.email", "test@example.com").directory(repoDir).start().waitFor()
        ProcessBuilder("git", "config", "user.name", "test").directory(repoDir).start().waitFor()
        ProcessBuilder("git", "add", ".").directory(repoDir).start().waitFor()
        ProcessBuilder("git", "commit", "-m", "init").directory(repoDir).start().waitFor()

        val driver = FlywheelDriver(
            apiKey = "test",
            repoDir = repoDir,
            intervalMs = 100,
            maxSlots = 5
        )

        val events = mutableListOf<FlywheelDriver.FlywheelEvent>()
        val job = launch {
            driver.events.take(4).toList(events)
        }
        yield()

        driver.cycle()

        assertTrue(events.any { it is FlywheelDriver.FlywheelEvent.SpecMissing && it.title == "Item 2" })

        job.cancel()
        driver.close()
    }
}
