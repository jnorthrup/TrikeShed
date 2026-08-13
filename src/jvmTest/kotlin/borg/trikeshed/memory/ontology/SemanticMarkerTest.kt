package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class SemanticMarkerTest {

    @Test
    fun semanticIsLongTermMemoryMarker() {
        val marker: LongTermMemory = Semantic
        assertTrue(marker.gloss.isNotEmpty())
    }
}
