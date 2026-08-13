package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class LongTailSolutionsMarkerTest {
    @Test
    fun longTailSolutionsIsAgentCentricMarker() {
        val marker: AgentCentricMemory = LongTailSolutions
        assertTrue(LongTailSolutions.gloss.isNotEmpty())
        assertTrue(marker is MemorySubject)
    }
}
