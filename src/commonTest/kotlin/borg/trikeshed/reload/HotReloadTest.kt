package borg.trikeshed.reload

import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HotReloadTest {
    @Test
    fun testHotReload() = runTest {
        val ops = InMemoryFileOperations()
        ops.write("config.txt", "v1")

        var reloads = 0
        val reloader = HotReloader(ops, "config.txt") {
            reloads++
        }

        val job = launch { reloader.start() }

        delay(10)
        ops.write("config.txt", "v2")
        delay(HotReloader.POLL_INTERVAL_MS * 2)

        job.cancel()

        assertEquals(1, reloads, "Should have reloaded once")
    }
}
