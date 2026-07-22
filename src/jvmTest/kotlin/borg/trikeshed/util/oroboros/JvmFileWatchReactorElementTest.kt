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
        val watcher = JvmFileWatchReactorElement(root.toString(), coroutineContext[kotlinx.coroutines.Job])
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
}
