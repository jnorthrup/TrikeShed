package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class LatentStateMarkerTest {

    @Test
    fun latentStateIsInternalParametricMarker() {
        val marker = LatentState
        assertTrue(marker is InternalParametric)
        assertTrue(marker.gloss.isNotEmpty())
    }
}
