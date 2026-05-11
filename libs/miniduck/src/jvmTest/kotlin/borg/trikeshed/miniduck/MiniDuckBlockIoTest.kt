package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files as JdkFiles

class MiniDuckBlockIoTest {
    @Test
    fun sealedBlockWritesAndReadsBackAsNdjsonBody() {
        val nestedDoc = JsonRowVec("body", "hello")
        val shellDoc = JsonRowVec("shell", null, childFactory = { 1 j { nestedDoc } })
        val viewDoc = JsonRowVec("title", "ship")
        val viewRow = ViewRowVec("doc-1", "by_title", 7) { viewDoc }
        val blobRow = BlobRowVec(byteArrayOf(1, 2, 3), "application/octet-stream") { 1 j { JsonRowVec("object", "{}") } }

        val block = BlockRowVec.mutable()
        block.append(shellDoc)
        block.append(viewRow)
        block.append(blobRow)
        block.seal()

        val file = JdkFiles.createTempFile("miniduck-block", ".ndjson")
        MiniDuckBlockFiles.write(file, block)

        val lines = JdkFiles.readAllLines(file)
        assertTrue(lines.size >= 2, "expected a header line plus at least one row line")

        val loaded = MiniDuckBlockFiles.read(file)
        assertEquals(BlockRowVec.State.SEALED, loaded.state)
        assertEquals(3, loaded.rowCount)

        val loadedShell = loaded.child[0] as DocRowVec
        assertEquals("shell", loadedShell.keys[0])
        assertNotNull(loadedShell.child)
        assertEquals("hello", (loadedShell.child!![0] as DocRowVec).cells[0])

        val loadedView = loaded.child[1] as ViewRowVec
        assertEquals("doc-1", loadedView.id)
        assertEquals("by_title", loadedView.getValue("key"))
        assertEquals(7, loadedView.getValue("value"))
        assertNotNull(loadedView.child)
        assertEquals("ship", (loadedView.child!![0] as DocRowVec).cells[0])

        val loadedBlob = loaded.child[2] as BlobRowVec
        assertEquals("application/octet-stream", loadedBlob.mimeType)
        assertNotNull(loadedBlob.child)
        assertEquals("object", (loadedBlob.child!![0] as JsonRowVec).nodeType)
    }

    @Test
    fun writeRefusesMutableBlocksBeforeSealing() {
        val block = BlockRowVec.mutable()
        block.append(JsonRowVec("x", "1"))

        val file = JdkFiles.createTempFile("miniduck-block-mutable", ".ndjson")
        assertFailsWith<IllegalStateException> {
            MiniDuckBlockFiles.write(file, block)
        }
    }

    @Test
    fun emptySealedBlockRoundTripsAsEmptyFileBody() {
        val block = BlockRowVec.mutable().seal()
        val file = JdkFiles.createTempFile("miniduck-empty-block", ".ndjson")

        MiniDuckBlockFiles.write(file, block)
        val loaded = MiniDuckBlockFiles.read(file)

        assertEquals(BlockRowVec.State.SEALED, loaded.state)
        assertEquals(0, loaded.rowCount)
        assertTrue(JdkFiles.readAllLines(file).isNotEmpty())
    }
}
