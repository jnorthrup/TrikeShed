package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class ModelWeightsMarkerTest {
    @Test
    fun modelWeightsIsInternalParametricMarker() {
        val marker: InternalParametric = ModelWeights
        assertTrue(ModelWeights.gloss.isNotEmpty())
        assertTrue(marker is MemorySubstrate)
    }
}
