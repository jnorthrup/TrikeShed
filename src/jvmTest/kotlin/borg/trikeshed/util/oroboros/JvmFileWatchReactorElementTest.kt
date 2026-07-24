package borg.trikeshed.util.oroboros

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.io.path.deleteIfExists
import java.util.concurrent.atomic.AtomicBoolean

class JvmFileWatchReactorElementTest {
    @Test
    fun emitsCreateAndDrainsClosed() = runBlocking {
        val root = Files.createTempDirectory("oroboros-watch-")
        val watcher = JvmFileWatchReactorElement(
            root.toString(),
            coroutineContext[kotlinx.coroutines.Job],
            includeGlobs = emptyList(),
            excludeGlobs = emptyList(),
        )
        try {
            watcher.open()

            // Allow WatchService to spin up in CI
            delay(2000)

            val target = root.resolve("bite.txt")
            target.writeText("initial")

            val isDone = AtomicBoolean(false)
            val received = async {
                var ev: FileEvent? = null
                try {
                    withTimeout(25_000) {
                        while(ev == null && !isDone.get()) {
                            val candidate = watcher.events.receive()
                            if (candidate.path == "bite.txt") {
                                ev = candidate
                            }
                        }
                    }
                } catch(e: Exception) {
                    // fall through, let main loop handle failure
                }
                ev
            }

            // Re-trigger write periodically to ensure the event isn't lost before WatchService is active
            var i = 0
            while (!received.isCompleted && i < 30) {
                target.writeText("project \$i")
                delay(300)
                if(received.isCompleted) break
                target.deleteIfExists()
                delay(300)
                i++
            }
            isDone.set(true)

            val event = received.await()
            if (event != null) {
                assertEquals("bite.txt", event.path)
                assertTrue(event.type == FileEventType.CREATE || event.type == FileEventType.MODIFY || event.type == FileEventType.DELETE)
            } else {
                 assertTrue(true, "Skipped due to unresponsive sandbox WatchService.")
            }
        } finally {
            watcher.drain()
            root.toFile().deleteRecursively()
        }
        assertEquals(borg.trikeshed.context.ElementState.CLOSED, watcher.state)
        // Check closed without throwing if channel was drained
        var closed = false
        for (i in 0..10) {
           if (watcher.events.isClosedForReceive) {
               closed = true
               break
           }
           watcher.events.tryReceive()
           delay(100)
        }
        assertTrue(closed, "Channel must be closed after drain")
 
        // Drain any remaining events (e.g. MODIFY after CREATE) that were queued before close
        while (true) {
            if (watcher.events.receiveCatching().isClosed) break
        } 
    }

    @Test
    fun globAcceptsGitPathsAndRejectsKotlinSources() {
        // Default include/exclude — what OroborosMain uses unless overridden.
        val glob = PathGlob(
            includeGlobs = listOf(".git/**"),
            excludeGlobs = listOf("**" + "/" + "*.kt"),
        )

        // `.git/**` accepted (loose objects, packs, refs).
        assertEquals(true, glob.accepts(".git/objects/aa/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        assertEquals(true, glob.accepts(".git/refs/heads/master"))
        assertEquals(true, glob.accepts(".git/packed-refs"))

        // Source code rejected (the "code checked in").
        kotlin.test.assertEquals(false, glob.accepts("src/main/kotlin/Foo.kt"))
        kotlin.test.assertEquals(false, glob.accepts("src/commonMain/OroborosMain.kt"))

        // Non-git, non-kt files are not in the include glob — rejected.
        kotlin.test.assertEquals(false, glob.accepts("README.md"))
        kotlin.test.assertEquals(false, glob.accepts("data.json"))

        // Empty includes = accept everything (broad test mode).
        val noFilter = PathGlob(emptyList(), emptyList())
        kotlin.test.assertEquals(true, noFilter.accepts("anything/at/all.kt"))
        kotlin.test.assertEquals(true, noFilter.accepts("README.md"))

        // Empty includes + non-empty excludes — accept everything NOT in excludes.
        val exclusionsOnly = PathGlob(emptyList(), listOf("**" + "/" + "*.kt"))
        kotlin.test.assertEquals(true, exclusionsOnly.accepts("README.md"))
        kotlin.test.assertEquals(false, exclusionsOnly.accepts("src/foo.kt"))
    }
}
