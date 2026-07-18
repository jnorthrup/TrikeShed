package borg.trikeshed.util.oroboros

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FileEWatcherTest {
    @Test
    fun testCoalescingAndIgnore() = runTest {
        val fileOps = FakeFileOperations()
        val watcher = FileEWatcher("/base", fileOps, listOf(".git", "ignored.txt"))

        watcher.recordEvent("/base/a.txt", FileEventType.CREATE)
        watcher.recordEvent("/base/a.txt", FileEventType.MODIFY)
        watcher.recordEvent("/base/.git/config", FileEventType.CREATE)
        watcher.recordEvent("/base/ignored.txt", FileEventType.CREATE)

        watcher.drain()
        val events = watcher.getChannel().receive()

        assertEquals(1, events.size)
        assertEquals("/base/a.txt", events[0].path)
        assertEquals(FileEventType.MODIFY, events[0].type)

        watcher.close()
    }

    @Test
    fun testScanCreatesModifiesDeletes() = runTest {
        val fileOps = FakeFileOperations()
        var fileContent = "initial".encodeToByteArray()
        var currentFiles = mutableListOf("a.txt")
        val statefulFileOps = object : FakeFileOperations() {
            override fun exists(filename: String) = true
            override fun listDir(path: String): List<String> {
                return if (path == "/base") currentFiles else emptyList()
            }
            override fun isDir(path: String) = path == "/base"
            override fun isFile(path: String) = path != "/base"
            override fun readAllBytes(filename: String): ByteArray = fileContent
        }

        val watcher = FileEWatcher("/base", statefulFileOps)

        // Scan 1: File created
        watcher.startProvisioning()
        watcher.drain()
        var events = watcher.getChannel().receive()
        assertEquals(1, events.size)
        assertEquals(FileEventType.CREATE, events[0].type)
        assertEquals("/base/a.txt", events[0].path)

        // Scan 2: File modified
        fileContent = "modified".encodeToByteArray()
        watcher.startProvisioning()
        watcher.drain()
        events = watcher.getChannel().receive()
        assertEquals(1, events.size)
        assertEquals(FileEventType.MODIFY, events[0].type)

        // Scan 3: File deleted
        currentFiles.clear()
        watcher.startProvisioning()
        watcher.drain()
        events = watcher.getChannel().receive()
        assertEquals(1, events.size)
        assertEquals(FileEventType.DELETE, events[0].type)

        watcher.close()
    }
}
