package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class MiniDuckBlobTest {
    @Test
    fun blobRoundTrip() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val blob = BlobRowVec(bytes, mimeType = "application/octet-stream")
        val block = BlockRowVec.mutable()
        block.append(blob)
        val sealed = block.seal()

        val text = MiniDuckBlockCodec.encode(sealed)
        val decoded = MiniDuckBlockCodec.decode(text)

        val first = decoded.child[0]
        assertTrue(first is BlobRowVec)
        val blobDecoded = first as BlobRowVec
        assertEquals(bytes.size, blobDecoded.bytes.size)
        assertTrue(bytes.contentEquals(blobDecoded.bytes))
    }
}
