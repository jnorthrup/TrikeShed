package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.channels.toList

class DummyFileOps : FileOperations {
    val files = mutableMapOf<String, ByteArray>()
    val dirs = mutableSetOf<String>()

    init {
        dirs.add("/h/.local/forge_home/agents/agent1")
    }

    override fun exists(filename: String) = files.containsKey(filename) || dirs.contains(filename)
    override fun isDir(path: String) = dirs.contains(path)
    override fun isFile(path: String) = files.containsKey(path)

    override fun readAllBytes(filename: String): ByteArray = files[filename] ?: throw Exception("Not found")

    override fun listDir(path: String): List<String> {
        val res = mutableSetOf<String>()
        val prefix = if (path.endsWith("/")) path else "$path/"

        for (f in files.keys) {
            if (f.startsWith(prefix)) {
                val rel = f.removePrefix(prefix)
                val part = rel.substringBefore("/")
                res.add(part)
            }
        }
        for (d in dirs) {
            if (d.startsWith(prefix) && d != path) {
                val rel = d.removePrefix(prefix)
                val part = rel.substringBefore("/")
                if (part.isNotEmpty()) res.add(part)
            }
        }
        return res.toList()
    }

    // Dummy implementations for unused methods
    override fun open(path: String, readOnly: Boolean) = 0
    override fun readAllLines(filename: String) = emptyList<String>()
    override fun readString(filename: String) = ""
    override fun write(filename: String, bytes: ByteArray) {}
    override fun write(filename: String, lines: List<String>) {}
    override fun write(filename: String, string: String) {}
    override fun cwd() = ""
    override fun streamLines(fileName: String, bufsize: Int) = emptySequence<Join<Long, ByteArray>>()
    override fun iterateLines(fileName: String, bufsize: Int) = emptyList<Join<Long, Series<Byte>>>()
    override fun mkdirs(path: String) { dirs.add(path) }
    override fun deleteRecursively(path: String) {}
    override fun resolvePath(vararg parts: String) = parts.joinToString("/")
    override fun readZip(path: String) = emptyList<Pair<String, ByteArray>>()
    override fun createTempDir(prefix: String) = ""
    override fun close(fd: Int) = 0
    override fun size(fd: Int) = 0L
}

class FileEWatcherTest {
    @Test
    fun testProvisionAndReconcile() = runTest {
        val fileOps = DummyFileOps()
        val fh = ForgeHome(DummySysOps("/h"))
        val watcher = FileEWatcher(fileOps, fh)

        // Setup some files
        val file1 = "/h/.local/forge_home/agents/agent1/a.txt"
        fileOps.files[file1] = byteArrayOf(1, 2, 3)
        fileOps.dirs.add("/h/.local/forge_home/agents/agent1/.git")
        fileOps.files["/h/.local/forge_home/agents/agent1/.git/config"] = byteArrayOf(0)

        watcher.provision("agent1")

        var event1 = watcher.events.receive()
        assertEquals(FileEvent.EventType.CREATED, event1.type)
        assertEquals("a.txt", event1.path)

        // modify file
        fileOps.files[file1] = byteArrayOf(4, 5, 6)
        watcher.reconcile("agent1")

        var event2 = watcher.events.receive()
        assertEquals(FileEvent.EventType.MODIFIED, event2.type)
        assertEquals("a.txt", event2.path)

        // duplicate modify - should coalesce (produce no event)
        watcher.reconcile("agent1")
        assertTrue(watcher.events.isEmpty)

        // delete file
        fileOps.files.remove(file1)
        watcher.reconcile("agent1")
        var event3 = watcher.events.receive()
        assertEquals(FileEvent.EventType.DELETED, event3.type)
        assertEquals("a.txt", event3.path)

        watcher.close()
    }
}
