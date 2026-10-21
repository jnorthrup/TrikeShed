package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.parse.*
import borg.trikeshed.web.patch.PatchKinds
import kotlin.test.*

class LcncOutputReceiptTest {
    @Test fun repeatedDocumentTextRetainsExactOutputWithinReceiptBudget() {
        val cas = CasStore.inMemory()
        val text = "pdf text ".repeat(8000)
        val output = mapOf("returns" to mapOf("text" to text), "outputs" to mapOf(
            "extract" to mapOf("text" to text), "source" to mapOf("text" to text)))
        assertEquals("payload_limit", ValueBudget().violation(output))
        val retained = LcncOutputReceipt.retain(cas, output)
        assertNull(ValueBudget().violation(retained))
        assertEquals(true, retained["outputPreview"])
        val bytes = assertNotNull(cas.get(ContentId(retained["outputCid"] as String)))
        assertEquals(jsonOf(output), bytes.decodeToString())
    }

    @Test fun inferredListsAndAnyHaveTheSameBrowserAndServerAcceptance() {
        val acceptance = LcncFacts.of(LcncContracts.all()).acceptance()
        for ((source, target) in listOf("List<json>" to "List<json>", "List<json>" to "Any",
            "List<json>" to "json", "ProposedRelationships" to "ReviewedRelationships"))
            assertEquals(LcncFacts.accepts(source, target), PatchKinds.compatible(source, target, acceptance))
        assertFalse(PatchKinds.compatible(null, "Any", acceptance))
    }
}
