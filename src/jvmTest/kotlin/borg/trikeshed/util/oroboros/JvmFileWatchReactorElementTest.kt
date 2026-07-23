package borg.trikeshed.util.oroboros

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            val received = async {
                withTimeout(5_000) { watcher.events.receive() }
            }
            root.resolve("bite.txt").writeText("project")
            val event = received.await()
            assertEquals("bite.txt", event.path)
            assertTrue(event.type == FileEventType.CREATE || event.type == FileEventType.MODIFY)
        } finally {
            watcher.drain()
            root.toFile().deleteRecursively()
        }
        assertEquals(borg.trikeshed.context.ElementState.CLOSED, watcher.state)

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
