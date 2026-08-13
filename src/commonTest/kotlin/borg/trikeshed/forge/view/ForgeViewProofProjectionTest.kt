package borg.trikeshed.forge.view

import borg.trikeshed.job.ContentId
import borg.trikeshed.viewserver.MapReduceProofReceipt
import borg.trikeshed.viewserver.ReducerIdentity
import borg.trikeshed.viewserver.ViewDefinitionIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForgeViewProofProjectionTest {
    @Test
    fun projectsVerifiedReceiptAsImmutableForgeProvenance() {
        val viewDefinition = ViewDefinitionIdentity("sales-by-region-v1".encodeToByteArray())
        val inputCids = listOf(
            ContentId.of("invoice-1".encodeToByteArray()),
            ContentId.of("invoice-2".encodeToByteArray()),
        )
        val receipt = MapReduceProofReceipt.mint(
            viewDefinition = viewDefinition,
            sourceDocumentCids = inputCids,
            reducer = ReducerIdentity("_sum", "builtin-v1"),
            outputBytes = "{\"west\":42}".encodeToByteArray(),
        )

        val projection = receipt.projectToForge(ViewProofVerification.Verified)

        requireNotNull(projection)
        assertEquals(ContentId.of(viewDefinition.canonicalBytes), projection.viewDefinitionCid)
        assertEquals(receipt.contentId, projection.receiptCid)
        assertEquals(ContentId.of(receipt.outputBytes), projection.outputCid)
        assertEquals(2, projection.inputCidCount)
        assertEquals("_sum", projection.reducerName)
        assertEquals("builtin-v1", projection.reducerVersion)
        assertEquals(ViewProofVerification.Verified, projection.verification)
    }

    @Test
    fun doesNotProjectTamperedReceiptAsVerified() {
        val tamperedReceipt = MapReduceProofReceipt.mint(
            viewDefinition = ViewDefinitionIdentity("sales-by-region-v1".encodeToByteArray()),
            sourceDocumentCids = emptyList(),
            reducer = ReducerIdentity("_count", "builtin-v1"),
            outputBytes = "2".encodeToByteArray(),
        )

        assertNull(tamperedReceipt.projectToForge(ViewProofVerification.Rejected))
    }
}
