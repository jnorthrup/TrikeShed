package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class CrossTaskTransferMarkerTest {

    @Test
    fun crossTaskTransferIsAgentCentricMarker() {
        assertNotNull(CrossTaskTransfer)
        assertIs<AgentCentricMemory>(CrossTaskTransfer)
        assertTrue(CrossTaskTransfer.gloss.isNotEmpty(), "Gloss should not be empty")
    }
}
