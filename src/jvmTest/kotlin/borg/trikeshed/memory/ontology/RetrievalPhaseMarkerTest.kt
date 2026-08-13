package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class RetrievalPhaseMarkerTest {
    @Test
    fun retrievalIsSubstratePhaseMarker() {
        val marker: SubstratePhase = Retrieval
        assertTrue(marker.gloss.isNotEmpty())
    }
}
