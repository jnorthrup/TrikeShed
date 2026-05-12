package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NioBlockWalTest {
    @Test
    fun appendPutKeepsPriorEntriesAndReplayRestoresBlocks() {
        val fs = TestFileOperations()
        val wal = NioBlockWal("/wal-root", fs)

        wal.appendPut("docs", "blk-0", blockOf("alice"))
        wal.appendPut("docs", "blk-1", blockOf("bob"))
        wal.appendRemove("docs", "blk-0")

        val store = InMemoryBlockStore()
        wal.replay(startSeq = 1L, store = store)

        assertNull(store.get("docs", "blk-0"))
        val surviving = assertNotNull(store.get("docs", "blk-1"))
        assertEquals(1, surviving.rowCount)
        val row = surviving.child!![0] as DocRowVec
        assertEquals("bob", row.cells[0])

        val walLines = fs.readString("/wal-root/wal.ndjson").lines()
        assertEquals(3, walLines.count { it.startsWith("{\"seq\":") })
    }

    @Test
    fun compactPreservesMultilineBlockPayloads() {
        val fs = TestFileOperations()
        val wal = NioBlockWal("/wal-root", fs)

        wal.appendPut("docs", "blk-0", blockOf("alice"))
        wal.appendPut("docs", "blk-1", blockOf("bob"))
        wal.compact(keepFromSeq = 2L)

        val store = InMemoryBlockStore()
        wal.replay(startSeq = 1L, store = store)

        assertNull(store.get("docs", "blk-0"))
        val surviving = assertNotNull(store.get("docs", "blk-1"))
        assertEquals(1, surviving.rowCount)
        val row = surviving.child!![0] as DocRowVec
        assertEquals("bob", row.cells[0])
        assertTrue(fs.readString("/wal-root/wal.ndjson").contains("\"id\":\"blk-1\""))
    }

    private fun blockOf(name: CharSequence): BlockRowVec {
        val block = BlockRowVec.mutable()
        block.append(DocRowVec(listOf("name"), listOf(name)))
        return block.seal()
    }

    private class TestFileOperations : FileOperations {
        private val files = linkedMapOf<CharSequence, CharSequence>()
        private val directories = linkedSetOf<CharSequence>()

        override fun readAllLines(filename: CharSequence): List<CharSequence> = readString(filename).lines()

        override fun readAllBytes(filename: CharSequence): ByteArray = readString(filename).encodeToByteArray()

        override fun readString(filename: CharSequence): CharSequence = files[filename] ?: ""

        override fun write(filename: CharSequence, bytes: ByteArray) {
            write(filename, bytes.decodeToString())
        }

        override fun write(filename: CharSequence, lines: List<CharSequence>) {
            write(filename, lines.joinToString("\n"))
        }

        override fun write(filename: CharSequence, string: CharSequence) {
            files[filename] = string
        }

        override fun cwd(): CharSequence = "/"

        override fun exists(filename: CharSequence): Boolean = filename in files || filename in directories

        override fun streamLines(fileName: CharSequence, bufsize: Int): Sequence<Join<Long, ByteArray>> =
            error("unused in test")

        override fun iterateLines(fileName: CharSequence, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
            error("unused in test")

        override fun listDir(path: CharSequence): List<CharSequence> =
            files.keys.filter { it.startsWith(path.trimEnd('/') + "/") }

        override fun isDir(path: CharSequence): Boolean = path in directories

        override fun isFile(path: CharSequence): Boolean = path in files

        override fun mkdirs(path: CharSequence) {
            directories += path
        }

        override fun deleteRecursively(path: CharSequence) {
            files.keys.filter { it == path || it.startsWith(path.trimEnd('/') + "/") }.toList().forEach(files::remove)
            directories.filter { it == path || it.startsWith(path.trimEnd('/') + "/") }.toList().forEach(directories::remove)
        }

        override fun resolvePath(vararg parts: CharSequence): CharSequence =
            parts.filter { it.isNotEmpty() }.joinToString("/") { it.trim('/') }.let { "/$it" }

        override fun readZip(path: CharSequence): List<Pair<CharSequence, ByteArray>> = error("unused in test")

        override fun createTempDir(prefix: CharSequence): CharSequence = "/tmp/$prefix"
    }
}
