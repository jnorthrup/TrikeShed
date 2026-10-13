package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CouchAttachmentGatewayTest {
    @Test
    fun attachmentMetadataDoesNotReadSourceBytes() {
        var reads = 0
        val cas = object : CasStore() {
            override fun get(cid: ContentId): ByteArray? {
                reads++
                return super.get(cid)
            }
        }
        val gateway = CouchAttachmentGateway(CouchStoreFactory.inMemory(), cas)
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        val ref = OroborosAttachmentRef("book.pdf", "application/pdf", bytes.size.toLong(),
            ContentId.of(bytes), "upload", "1", 0)
        gateway.putAttachment(ref, bytes)

        assertEquals(ref, gateway.getAttachmentRef(ref.path))
        assertEquals(0, reads, "recognition must not materialize the document")
        assertContentEquals(bytes, gateway.getAttachment(ref.path)?.second)
        assertEquals(1, reads)

        gateway.deleteAttachment(ref.path, ref.revision)
        assertNull(gateway.getAttachmentRef(ref.path))
        assertNull(gateway.getAttachment(ref.path))
        assertEquals(1, reads, "deleted metadata must not fetch its former bytes")
    }
}
