package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files

class MiniDuckBlockIoTest {
    @Test
    fun sealedBlockWritesAndReadsBackAsNdjsonBody() {
        val nestedDoc = DocRowVec(listOf("body"), listOf("hello"))
        val shellDoc = DocRowVec(emptyList(), emptyList(), child = 1 j { nestedDoc })
        val viewDoc = DocRowVec(listOf("title"), listOf("ship"))
        val viewRow = ViewRowVec("doc-1", "by_title", 7) { viewDoc }
        val blobRow = BlobRowVec(byteArrayOf(1, 2, 3), "application/octet-stream") { 1 j { JsonRowVec("object", "{}") } }

        val block = BlockRowVec.mutable()
        block.append(shellDoc)
        block.append(viewRow)
        block.append(blobRow)
        block.seal()

        val file = Files.createTempFile("miniduck-block", ".ndjson")
        MiniDuckBlockFiles.write(file, block)

        val lines = Files.readAllLines(file)
        assertTrue(lines.size >= 2, "expected a header line plus at least one row line")

        val loaded = MiniDuckBlockFiles.read(file)
        assertEquals(BlockRowVec.State.SEALED, loaded.state)
        assertEquals(3, loaded.rowCount)

        val loadedShell = loaded.child[0] as DocRowVec
        assertTrue(loadedShell.isShell)
        assertNotNull(loadedShell.child)
        assertEquals("hello", (loadedShell.child!![0] as DocRowVec)["body"])

        val loadedView = loaded.child[1] as ViewRowVec
        assertEquals("doc-1", loadedView.id)
        assertEquals("by_title", loadedView.getValue("key"))
        assertEquals(7, loadedView.getValue("value"))
        assertNotNull(loadedView.child)
        assertEquals("ship", (loadedView.child!![0] as DocRowVec)["title"])

        val loadedBlob = loaded.child[2] as BlobRowVec
        assertEquals("application/octet-stream", loadedBlob.mimeType)
        assertNotNull(loadedBlob.child)
        assertEquals("object", (loadedBlob.child!![0] as JsonRowVec).nodeType)
    }

    @Test
    fun writeRefusesMutableBlocksBeforeSealing() {
        val block = BlockRowVec.mutable()
        block.append(DocRowVec(listOf("x"), listOf(1)))

        val file = Files.createTempFile("miniduck-block-mutable", ".ndjson")
        assertFailsWith<IllegalStateException> {
            MiniDuckBlockFiles.write(file, block)
        }
    }

    @Test
    fun emptySealedBlockRoundTripsAsEmptyFileBody() {
        val block = BlockRowVec.mutable().seal()
        val file = Files.createTempFile("miniduck-empty-block", ".ndjson")

        MiniDuckBlockFiles.write(file, block)
        val loaded = MiniDuckBlockFiles.read(file)

        assertEquals(BlockRowVec.State.SEALED, loaded.state)
        assertEquals(0, loaded.rowCount)
        assertTrue(Files.readAllLines(file).isNotEmpty())
    }
}
