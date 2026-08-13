package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class SensoryMarkerTest {
    @Test
    fun sensoryIsShortTermMemoryMarker() {
        val sensory: ShortTermMemory = Sensory
        assertTrue(sensory.gloss.isNotEmpty(), "Gloss should not be empty")
    }
}
