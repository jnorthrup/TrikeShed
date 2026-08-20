package borg.trikeshed.viewserver

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MapReduceProofReceiptTest {
    @Test
    fun receiptCidIsStableAndSensitiveToEveryCanonicalComponent() {
        val documentOne = ContentId.of("document-one".encodeToByteArray())
        val documentTwo = ContentId.of("document-two".encodeToByteArray())
        val base = receipt(
            viewDefinition = "view-v1".encodeToByteArray(),
            sourceDocumentCids = listOf(documentOne, documentTwo),
            reducer = ReducerIdentity("sum", "1"),
            output = "result-v1".encodeToByteArray(),
        )

        assertEquals(base.contentId, receipt(
            viewDefinition = "view-v1".encodeToByteArray(),
            sourceDocumentCids = listOf(documentOne, documentTwo),
            reducer = ReducerIdentity("sum", "1"),
            output = "result-v1".encodeToByteArray(),
        ).contentId)
        assertNotEquals(base.contentId, receipt("view-v2".encodeToByteArray(), listOf(documentOne, documentTwo), ReducerIdentity("sum", "1"), "result-v1".encodeToByteArray()).contentId)
        assertNotEquals(base.contentId, receipt("view-v1".encodeToByteArray(), listOf(documentTwo, documentOne), ReducerIdentity("sum", "1"), "result-v1".encodeToByteArray()).contentId)
        assertNotEquals(base.contentId, receipt("view-v1".encodeToByteArray(), listOf(ContentId.of("document-three".encodeToByteArray()), documentTwo), ReducerIdentity("sum", "1"), "result-v1".encodeToByteArray()).contentId)
        assertNotEquals(base.contentId, receipt("view-v1".encodeToByteArray(), listOf(documentOne, documentTwo), ReducerIdentity("count", "1"), "result-v1".encodeToByteArray()).contentId)
        assertNotEquals(base.contentId, receipt("view-v1".encodeToByteArray(), listOf(documentOne, documentTwo), ReducerIdentity("sum", "2"), "result-v1".encodeToByteArray()).contentId)
        assertNotEquals(base.contentId, receipt("view-v1".encodeToByteArray(), listOf(documentOne, documentTwo), ReducerIdentity("sum", "1"), "result-v2".encodeToByteArray()).contentId)
    }

    @Test
    fun lengthDelimitedComponentsDistinguishAmbiguousBoundaries() {
        val left = receipt(
            viewDefinition = "ab".encodeToByteArray(),
            sourceDocumentCids = emptyList(),
            reducer = ReducerIdentity("c", ""),
            output = byteArrayOf(),
        )
        val right = receipt(
            viewDefinition = "a".encodeToByteArray(),
            sourceDocumentCids = emptyList(),
            reducer = ReducerIdentity("bc", ""),
            output = byteArrayOf(),
        )

        kotlin.test.assertFalse(left.canonicalBytes.contentEquals(right.canonicalBytes))
        assertNotEquals(left.contentId, right.contentId)
    }

    private fun receipt(
        viewDefinition: ByteArray,
        sourceDocumentCids: List<ContentId>,
        reducer: ReducerIdentity,
        output: ByteArray,
    ): MapReduceProofReceipt = MapReduceProofReceipt.mint(
        viewDefinition = ViewDefinitionIdentity(viewDefinition),
        sourceDocumentCids = sourceDocumentCids,
        reducer = reducer,
        outputBytes = output,
    )
}
