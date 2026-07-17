package borg.trikeshed.couch

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductionCouchIngressTest {

    private fun mockContentId(doc: Document): ContentId {
        return ContentId.of(doc.fields.joinToString { it.value.toString() }.encodeToByteArray())
    }

    @Test
    fun testProductionIngress() {
        val head = CouchHeadProjection()
        val changes = CouchChangesProjection()

        val ingress = ProductionCouchIngress(
            head = head,
            commitBoundary = { frame ->
                head.applyCommit(frame)
                changes.applyCommit(frame)
            },
            contentIdFn = ::mockContentId
        )

        val doc1 = Document("doc1", listOf(Field("name", "Alice")))
        assertTrue(ingress.putIntent(doc1, null))

        val rev1 = head.getRev("doc1")
        assertNotNull(rev1)
        assertTrue(rev1.startsWith("1-sha256:"))

        val doc1Update = Document("doc1", listOf(Field("name", "Bob")))
        assertFalse(ingress.putIntent(doc1Update, "wrong-rev"))
        assertTrue(ingress.putIntent(doc1Update, rev1), "Update should return true for valid rev")

        val rev2 = head.getRev("doc1")
        assertNotNull(rev2)
        assertTrue(rev2.startsWith("2-sha256:"))
    }
}
